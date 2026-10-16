package com.factoryflow.production.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.production.domain.Equipment;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.domain.ProductionResult;
import com.factoryflow.production.domain.StepResult;
import com.factoryflow.production.domain.Vin;
import com.factoryflow.production.domain.WorkOrder;
import com.factoryflow.production.domain.WorkOrderStatus;
import com.factoryflow.production.domain.WorkOrderStep;
import com.factoryflow.production.dto.response.ProductionResultResponse;
import com.factoryflow.production.dto.response.WorkOrderDetailResponse;
import com.factoryflow.production.dto.response.WorkOrderResponse;
import com.factoryflow.production.dto.response.WorkOrderStepResponse;
import com.factoryflow.production.event.publisher.payload.StepDispatched;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.production.repository.EquipmentRepository;
import com.factoryflow.production.repository.ProductionResultRepository;
import com.factoryflow.production.repository.WorkOrderRepository;
import com.factoryflow.production.repository.WorkOrderStepRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MES 작업지시: 작업자의 공정 시작·종료와 실적 (docs/specs/production-work-order.md). */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderService {

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderStepRepository workOrderStepRepository;
    private final ProductionResultRepository productionResultRepository;
    private final EquipmentRepository equipmentRepository;
    private final ProductionService productionService;
    private final EventPublisher eventPublisher;

    /** 작업지시 목록 (RELEASED 또는 IN_PROGRESS), id 순서로 lastId 다음부터 size 개. 다음 페이지가 있는지 알려고 하나 더 읽는다. */
    @Transactional(readOnly = true)
    public CursorPage<WorkOrderResponse> findWorkOrders(String status, Long lastId, int size) {
        List<WorkOrder> rows =
                findWorkOrdersAfter(WorkOrderStatus.valueOf(status), lastId, size + 1);
        Map<Long, List<WorkOrderStep>> stepsByWorkOrderId = stepsByWorkOrderId(rows);
        List<WorkOrderResponse> items =
                rows.stream()
                        .map(
                                w ->
                                        WorkOrderResponse.of(
                                                w,
                                                currentProcess(
                                                        stepsByWorkOrderId.getOrDefault(
                                                                w.getId(), List.of()))))
                        .toList();
        return CursorPage.of(items, size, WorkOrderResponse::workOrderId);
    }

    @Transactional(readOnly = true)
    public WorkOrderDetailResponse findWorkOrder(Long workOrderId) {
        WorkOrder workOrder = findWorkOrderById(workOrderId);
        return detail(workOrder, findSteps(workOrderId));
    }

    /**
     * 설비에서 공정을 시작한다. 앞 공정이 모두 DONE 이고 이 공정이 PENDING 이며, 설비가 이 공정의 것이고 RUNNING 일 때만 된다. 작업지시의 첫
     * 시작(프레스)이면 생산 건도 시작한다 (이후 취소 불가). 프레스 불량 재작업으로 다시 시작할 때는 생산 건을 건드리지 않는다.
     */
    @Transactional
    public WorkOrderDetailResponse startStep(
            Long workOrderId, ProductionProcess process, String equipmentCode) {
        WorkOrder workOrder =
                beginStep(
                        workOrderId,
                        process,
                        equipmentCode,
                        (step, now) -> step.start(equipmentCode, now));
        return detail(workOrder, findSteps(workOrderId));
    }

    /** 로봇 공정에서 배정을 받을 수 있는 설비: RUNNING 이고 맡은 공정이 없는 설비, 코드 순서. */
    @Transactional(readOnly = true)
    public List<String> findFreeEquipmentCodes(ProductionProcess process) {
        return findEquipmentsOf(process).stream()
                .filter(e -> e.isRunning() && !e.isOccupied())
                .map(Equipment::getCode)
                .toList();
    }

    /** 이 공정을 지금 배정할 수 있는 작업지시 id, 먼저 발행된 순서로 limit 개 (excludedIds 제외). */
    @Transactional(readOnly = true)
    public List<Long> findDispatchableWorkOrderIds(
            ProductionProcess process, Collection<Long> excludedIds, int limit) {
        return findDispatchable(process, excludedIds, limit);
    }

    /**
     * 로봇 공정을 설비에 배정한다: 공정 DISPATCHED + production.step-dispatched. 작업지시의 첫 시작(프레스)이면 생산 건도 시작한다 (생산
     * 순서 확정, 이후 취소 불가). 시작과 같은 조건을 다시 확인한다.
     */
    @Transactional
    public void dispatch(Long workOrderId, ProductionProcess process, String equipmentCode) {
        beginStep(workOrderId, process, equipmentCode, (step, now) -> step.dispatch(equipmentCode));
        eventPublisher.publish(new StepDispatched(workOrderId, process, equipmentCode));
    }

    /**
     * 공정 시작(작업자)과 배정(스케줄러)의 공통: 작업지시·순서·설비를 확인하고, 공정을 바꾸고(begin: 시작 또는 배정), 설비를 점유하고, 작업지시의 첫 시작이면
     * 생산 건도 시작한 뒤 저장한다. 두 경로가 같은 규칙을 쓰게 하려고 한곳에 둔다.
     */
    private WorkOrder beginStep(
            Long workOrderId,
            ProductionProcess process,
            String equipmentCode,
            BiConsumer<WorkOrderStep, LocalDateTime> begin) {
        WorkOrder workOrder = findOpenWorkOrder(workOrderId);
        List<WorkOrderStep> steps = findSteps(workOrderId);
        WorkOrderStep step = stepOf(steps, process);
        requireStartable(steps, step);
        Equipment equipment = findEquipment(equipmentCode);
        requireUsable(equipment, process);
        LocalDateTime now = LocalDateTime.now();
        begin.accept(step, now);
        equipment.occupy(step.getId());
        workOrder.recordStepChange(now);
        if (workOrder.start(now)) {
            productionService.startProduction(workOrder.getProductionId());
        }
        flushOrConflict(workOrderId);
        return workOrder;
    }

    /** 설비의 시작 보고: 그 설비에 배정된(DISPATCHED) 공정이면 IN_PROGRESS. 아니면 무시한다. */
    @Transactional
    public void reportStepStarted(
            Long workOrderId, ProductionProcess process, String equipmentCode) {
        reportableStep(workOrderId, process, equipmentCode, WorkOrderStep::isDispatched)
                .ifPresent(
                        step -> {
                            LocalDateTime now = LocalDateTime.now();
                            step.startByEquipment(now);
                            findWorkOrderById(workOrderId).recordStepChange(now);
                        });
    }

    /** 설비의 완료 보고: 그 설비에서 진행 중인 공정이면 실적(작업자 없음)을 남기고 양품 DONE, 불량 PENDING(다시 배정). 아니면 무시한다. */
    @Transactional
    public void reportStepCompleted(
            Long workOrderId,
            ProductionProcess process,
            String equipmentCode,
            StepResult result,
            String defectReason) {
        reportableStep(workOrderId, process, equipmentCode, WorkOrderStep::isInProgress)
                .ifPresent(
                        step ->
                                finishStep(
                                        findWorkOrderById(workOrderId),
                                        step,
                                        result,
                                        defectReason,
                                        null));
    }

    /** 설비 보고를 반영할 공정. 작업지시가 없거나 끝났거나, 공정 상태·설비가 맞지 않으면 empty (늦거나 꼬인 보고, 수동 개입). */
    private Optional<WorkOrderStep> reportableStep(
            Long workOrderId,
            ProductionProcess process,
            String equipmentCode,
            Predicate<WorkOrderStep> expected) {
        Optional<WorkOrder> workOrder = findWorkOrderIfExists(workOrderId);
        if (workOrder.isEmpty() || workOrder.get().isClosed()) {
            log.info("작업지시가 없거나 끝나 설비 보고 무시: workOrderId={}", workOrderId);
            return Optional.empty();
        }
        WorkOrderStep step = stepOf(findSteps(workOrderId), process);
        if (!expected.test(step) || !equipmentCode.equals(step.getEquipmentCode())) {
            log.info(
                    "공정 상태·설비가 맞지 않아 설비 보고 무시: workOrderId={} process={} status={} equipment={} reported={}",
                    workOrderId,
                    process,
                    step.getStatus(),
                    step.getEquipmentCode(),
                    equipmentCode);
            return Optional.empty();
        }
        return Optional.of(step);
    }

    /** 진행 중인 공정을 끝내고 실적을 남긴다. 양품이면 DONE, 불량이면 실적만 남기고 재작업(PENDING). 이벤트는 없다. */
    @Transactional
    public WorkOrderDetailResponse completeStep(
            Long workOrderId,
            ProductionProcess process,
            StepResult result,
            String defectReason,
            String workerId) {
        WorkOrder workOrder = findOpenWorkOrder(workOrderId);
        List<WorkOrderStep> steps = findSteps(workOrderId);
        WorkOrderStep step = stepOf(steps, process);
        if (!step.isInProgress()) {
            throw invalidStepStatus(step);
        }
        finishStep(workOrder, step, result, defectReason, workerId);
        flushOrConflict(workOrderId);
        return detail(workOrder, steps);
    }

    /**
     * 공정 종료(작업자)와 설비 완료 보고의 공통: 실적을 남기고(불량 사유는 DEFECT 일 때만), 설비를 놓고, 공정을 마무리하고(양품 DONE, 불량
     * PENDING), 작업지시 변경을 기록한다. 검사 양품이면 차를 완성한다. 두 경로가 같은 완료 규칙을 쓰게 하려고 한곳에 둔다. workerId 는 설비 보고면
     * null.
     */
    private void finishStep(
            WorkOrder workOrder,
            WorkOrderStep step,
            StepResult result,
            String defectReason,
            String workerId) {
        LocalDateTime now = LocalDateTime.now();
        String reason = result == StepResult.DEFECT ? defectReason : null;
        productionResultRepository.save(
                ProductionResult.record(step, result, reason, workerId, now));
        findEquipment(step.getEquipmentCode()).release();
        step.finish(result, now);
        workOrder.recordStepChange(now);
        if (result == StepResult.GOOD && step.getProcess().isLast()) {
            completeWorkOrder(workOrder, now);
        }
    }

    /** 마지막 공정(검사)이 양품으로 끝났다: VIN 을 발행하고 작업지시·생산 건을 완료한다 (docs/specs/production-completion.md). */
    private void completeWorkOrder(WorkOrder workOrder, LocalDateTime now) {
        String vin = Vin.issue(workOrder.getProductId(), workOrder.getId(), now.getYear());
        workOrder.complete(vin, now);
        productionService.completeProduction(workOrder.getProductionId(), vin);
    }

    /**
     * 설비가 고장·정지해 그 설비가 맡은 공정(배정·진행 중일 때만)을 시작 전으로 되돌린다 (EquipmentService 와 같은 트랜잭션). 작업지시 변경을 기록해
     * 같은 작업지시를 동시에 바꾸는 작업자 요청과 version 으로 부딪히게 한다. 작업지시·생산 건 상태는 그대로다.
     */
    @Transactional
    public void interruptStep(Long stepId) {
        WorkOrderStep step = findStep(stepId);
        if (!step.isDispatched() && !step.isInProgress()) {
            log.info(
                    "배정·진행 중인 공정이 아니라 되돌리지 않음: workOrderId={} process={} status={}",
                    step.getWorkOrderId(),
                    step.getProcess(),
                    step.getStatus());
            return;
        }
        step.interrupt();
        findWorkOrderById(step.getWorkOrderId()).recordStepChange(LocalDateTime.now());
    }

    /** 앞 공정이 모두 DONE 이고 이 공정이 PENDING 이어야 한다. 그래서 한 작업지시에서 진행 중인 공정은 하나뿐이다. */
    private static void requireStartable(List<WorkOrderStep> steps, WorkOrderStep step) {
        if (!step.isPending()) {
            throw invalidStepStatus(step);
        }
        boolean previousDone =
                steps.stream()
                        .filter(s -> s.getSeq() < step.getSeq())
                        .allMatch(WorkOrderStep::isDone);
        if (!previousDone) {
            throw new CustomException(ProductionErrorCode.STEP_OUT_OF_ORDER);
        }
    }

    /** 이 공정의 설비이고, 가동 중이고, 다른 공정을 맡고 있지 않아야 한다. 같은 설비를 동시에 잡으면 설비 version 충돌로 한쪽만 성공한다. */
    private void requireUsable(Equipment equipment, ProductionProcess process) {
        if (equipment.getProcess() != process) {
            throw new CustomException(ProductionErrorCode.EQUIPMENT_PROCESS_MISMATCH);
        }
        if (!equipment.isRunning()) {
            throw new CustomException(ProductionErrorCode.EQUIPMENT_NOT_RUNNING);
        }
        if (equipment.isOccupied()) {
            throw new CustomException(ProductionErrorCode.EQUIPMENT_BUSY);
        }
    }

    private static CustomException invalidStepStatus(WorkOrderStep step) {
        log.info(
                "공정 상태가 맞지 않아 거절: workOrderId={} process={} status={}",
                step.getWorkOrderId(),
                step.getProcess(),
                step.getStatus());
        return new CustomException(ProductionErrorCode.INVALID_STEP_STATUS);
    }

    /** 라우팅 순서로 끝나지 않은 첫 공정. 모두 끝났으면 null. */
    private static ProductionProcess currentProcess(List<WorkOrderStep> steps) {
        return steps.stream()
                .filter(s -> !s.isDone())
                .map(WorkOrderStep::getProcess)
                .findFirst()
                .orElse(null);
    }

    private static WorkOrderStep stepOf(List<WorkOrderStep> steps, ProductionProcess process) {
        return steps.stream()
                .filter(s -> s.getProcess() == process)
                .findFirst()
                .orElseThrow(() -> new CustomException(ProductionErrorCode.WORK_ORDER_NOT_FOUND));
    }

    /** 공정 5개와 공정별 실적. 실적은 한 번에 읽는다. */
    private WorkOrderDetailResponse detail(WorkOrder workOrder, List<WorkOrderStep> steps) {
        Map<Long, List<ProductionResultResponse>> resultsByStepId =
                findResults(steps.stream().map(WorkOrderStep::getId).toList()).stream()
                        .collect(
                                Collectors.groupingBy(
                                        ProductionResult::getWorkOrderStepId,
                                        Collectors.mapping(
                                                ProductionResultResponse::from,
                                                Collectors.toList())));
        return WorkOrderDetailResponse.of(
                workOrder,
                steps.stream()
                        .map(
                                s ->
                                        WorkOrderStepResponse.of(
                                                s,
                                                resultsByStepId.getOrDefault(s.getId(), List.of())))
                        .toList());
    }

    private Map<Long, List<WorkOrderStep>> stepsByWorkOrderId(List<WorkOrder> workOrders) {
        if (workOrders.isEmpty()) {
            return Map.of();
        }
        return findStepsOf(workOrders.stream().map(WorkOrder::getId).toList()).stream()
                .collect(Collectors.groupingBy(WorkOrderStep::getWorkOrderId));
    }

    /** 공정을 바꿀 작업지시. 취소됐으면 WORK_ORDER_CLOSED. */
    private WorkOrder findOpenWorkOrder(Long workOrderId) {
        WorkOrder workOrder = findWorkOrderById(workOrderId);
        if (workOrder.isClosed()) {
            throw new CustomException(ProductionErrorCode.WORK_ORDER_CLOSED);
        }
        return workOrder;
    }

    private WorkOrder findWorkOrderById(Long workOrderId) {
        return workOrderRepository
                .findById(workOrderId)
                .orElseThrow(() -> new CustomException(ProductionErrorCode.WORK_ORDER_NOT_FOUND));
    }

    private List<WorkOrder> findWorkOrdersAfter(WorkOrderStatus status, Long lastId, int limit) {
        return workOrderRepository.findAllByStatusAndIdGreaterThanOrderByIdAsc(
                status, lastId, Limit.of(limit));
    }

    /** 설비가 맡은 공정. 없으면 데이터가 꼬인 것이라 원인이 보이게 STEP_NOT_FOUND. */
    private WorkOrderStep findStep(Long stepId) {
        return workOrderStepRepository
                .findById(stepId)
                .orElseThrow(() -> new CustomException(ProductionErrorCode.STEP_NOT_FOUND));
    }

    private List<WorkOrderStep> findSteps(Long workOrderId) {
        return workOrderStepRepository.findAllByWorkOrderIdOrderBySeqAsc(workOrderId);
    }

    private List<WorkOrderStep> findStepsOf(List<Long> workOrderIds) {
        return workOrderStepRepository.findAllByWorkOrderIdInOrderBySeqAsc(workOrderIds);
    }

    private List<ProductionResult> findResults(List<Long> stepIds) {
        return productionResultRepository.findAllByWorkOrderStepIdInOrderByIdAsc(stepIds);
    }

    private Optional<WorkOrder> findWorkOrderIfExists(Long workOrderId) {
        return workOrderRepository.findById(workOrderId);
    }

    private List<Long> findDispatchable(
            ProductionProcess process, Collection<Long> excludedIds, int limit) {
        return workOrderStepRepository.findDispatchableWorkOrderIds(process, excludedIds, limit);
    }

    private List<Equipment> findEquipmentsOf(ProductionProcess process) {
        return equipmentRepository.findAllByProcessAndIsDeletedFalseOrderByCodeAsc(process);
    }

    private Equipment findEquipment(String equipmentCode) {
        return equipmentRepository
                .findByCodeAndIsDeletedFalse(equipmentCode)
                .orElseThrow(() -> new CustomException(ProductionErrorCode.EQUIPMENT_NOT_FOUND));
    }

    /** 응답을 만들기 전에 저장해 version 을 확인한다. 다른 요청이 먼저 바꿨으면 PRODUCTION_CONFLICT. */
    private void flushOrConflict(Long workOrderId) {
        try {
            workOrderRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.info("다른 작업이 먼저 바꿔 거절: workOrderId={}", workOrderId);
            throw new CustomException(ProductionErrorCode.PRODUCTION_CONFLICT);
        }
    }
}

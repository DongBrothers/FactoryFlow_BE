package com.factoryflow.mes.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.mes.domain.Equipment;
import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.domain.ProductionResult;
import com.factoryflow.mes.domain.StepResult;
import com.factoryflow.mes.domain.StepStatus;
import com.factoryflow.mes.domain.Vin;
import com.factoryflow.mes.domain.WorkOrder;
import com.factoryflow.mes.domain.WorkOrderPartScan;
import com.factoryflow.mes.domain.WorkOrderStatus;
import com.factoryflow.mes.domain.WorkOrderStep;
import com.factoryflow.mes.dto.request.ScannedLotRequest;
import com.factoryflow.mes.dto.response.ProductionResultResponse;
import com.factoryflow.mes.dto.response.WorkOrderDetailResponse;
import com.factoryflow.mes.dto.response.WorkOrderResponse;
import com.factoryflow.mes.dto.response.WorkOrderStepResponse;
import com.factoryflow.mes.event.listener.payload.ProductionReleased;
import com.factoryflow.mes.event.publisher.payload.ProductionStarted;
import com.factoryflow.mes.event.publisher.payload.ScannedLot;
import com.factoryflow.mes.event.publisher.payload.StepDispatched;
import com.factoryflow.mes.event.publisher.payload.VehicleCompleted;
import com.factoryflow.mes.event.publisher.payload.WorkOrderCancelRejected;
import com.factoryflow.mes.event.publisher.payload.WorkOrderCancelled;
import com.factoryflow.mes.exception.MesErrorCode;
import com.factoryflow.mes.repository.EquipmentRepository;
import com.factoryflow.mes.repository.ProductionResultRepository;
import com.factoryflow.mes.repository.WorkOrderPartScanRepository;
import com.factoryflow.mes.repository.WorkOrderRepository;
import com.factoryflow.mes.repository.WorkOrderStepRepository;
import java.time.LocalDateTime;
import java.util.Arrays;
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

/**
 * MES 작업지시: 발행 수신, 공정 시작·종료와 실적, 설비 배정, 취소 판단 (docs/specs/production-work-order.md,
 * docs/specs/mes-split.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderService {

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderStepRepository workOrderStepRepository;
    private final ProductionResultRepository productionResultRepository;
    private final WorkOrderPartScanRepository workOrderPartScanRepository;
    private final EquipmentRepository equipmentRepository;
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

    /**
     * production.released: 생산 담당이 발행한 차에 작업지시 1건(RELEASED)과 라우팅 순서대로 공정 5개(PENDING). 같은 생산 건의 작업지시가
     * 이미 있으면(다시 온 발행, 먼저 온 취소 요청으로 만든 취소 작업지시) 건너뛴다 (docs/specs/mes-split.md).
     */
    @Transactional
    public void release(ProductionReleased event) {
        if (hasWorkOrder(event.productionId())) {
            log.info("작업지시가 이미 있어 발행 건너뜀: productionId={}", event.productionId());
            return;
        }
        WorkOrder workOrder =
                workOrderRepository.save(
                        WorkOrder.release(
                                event.productionId(),
                                event.orderId(),
                                event.productId(),
                                LocalDateTime.now()));
        workOrderStepRepository.saveAll(
                Arrays.stream(ProductionProcess.values())
                        .map(process -> WorkOrderStep.plan(workOrder.getId(), process))
                        .toList());
    }

    /**
     * production.release-cancel-requested: 첫 공정을 시작하기 전이면 작업지시를 취소하고 mes.work-order-cancelled,
     * 시작했으면 mes.work-order-cancel-rejected. 발행 이벤트보다 먼저 왔으면 취소된 작업지시를 남겨 뒤에 온 발행을 막는다
     * (docs/specs/mes-split.md).
     */
    @Transactional
    public void cancelRelease(Long productionId, Long orderId) {
        Optional<WorkOrder> found = findWorkOrderOfProduction(productionId);
        if (found.isEmpty()) {
            log.info("발행보다 취소 요청이 먼저 와 취소된 작업지시를 남김: productionId={}", productionId);
            workOrderRepository.save(
                    WorkOrder.cancelBeforeRelease(productionId, orderId, LocalDateTime.now()));
            eventPublisher.publish(new WorkOrderCancelled(productionId));
            return;
        }
        WorkOrder workOrder = found.get();
        if (workOrder.cancel() || workOrder.isClosed()) {
            eventPublisher.publish(new WorkOrderCancelled(productionId));
            return;
        }
        log.info(
                "첫 공정을 이미 시작해 취소 거절: productionId={} status={}",
                productionId,
                workOrder.getStatus());
        eventPublisher.publish(new WorkOrderCancelRejected(productionId, workOrder.getStatus()));
    }

    @Transactional(readOnly = true)
    public WorkOrderDetailResponse findWorkOrder(Long workOrderId) {
        WorkOrder workOrder = findWorkOrderById(workOrderId);
        return detail(workOrder, findSteps(workOrderId));
    }

    /**
     * 설비에서 공정을 시작한다. 앞 공정이 모두 DONE 이고 이 공정이 PENDING 이며, 설비가 이 공정의 것이고 RUNNING 일 때만 된다. 작업지시의 첫
     * 시작(프레스)이면 production 에 생산 시작을 알린다 (이후 취소 불가). 프레스 불량 재작업으로 다시 시작할 때는 알리지 않는다.
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
     * 로봇 공정을 설비에 배정한다: 공정 DISPATCHED + mes.step-dispatched. 작업지시의 첫 시작(프레스)이면 production 에 생산 시작을
     * 알린다 (생산 순서 확정, 이후 취소 불가). 시작과 같은 조건을 다시 확인한다.
     */
    @Transactional
    public void dispatch(Long workOrderId, ProductionProcess process, String equipmentCode) {
        beginStep(
                workOrderId,
                process,
                equipmentCode,
                (step, now) -> step.dispatch(equipmentCode, now));
        int dispatchSeq = stepOf(findSteps(workOrderId), process).getDispatchSeq();
        eventPublisher.publish(
                new StepDispatched(workOrderId, process, equipmentCode, dispatchSeq));
    }

    /**
     * 공정 시작(작업자)과 배정(스케줄러)의 공통: 작업지시·순서·설비를 확인하고, 공정을 바꾸고(begin: 시작 또는 배정), 설비를 점유하고 저장한 뒤, 작업지시의 첫
     * 시작이면 mes.production-started 를 보낸다. 두 경로가 같은 규칙을 쓰게 하려고 한곳에 둔다.
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
        boolean firstStart = workOrder.start(now);
        flushOrConflict(workOrderId);
        if (firstStart) {
            eventPublisher.publish(new ProductionStarted(workOrder.getProductionId(), workOrderId));
        }
        return workOrder;
    }

    /** 설비의 시작 보고: 그 설비에 이번 회차로 배정된(DISPATCHED) 공정이면 IN_PROGRESS. 아니면 무시한다. */
    @Transactional
    public void reportStepStarted(
            Long workOrderId,
            ProductionProcess process,
            String equipmentCode,
            Integer dispatchSeq) {
        reportableStep(
                        workOrderId,
                        process,
                        equipmentCode,
                        dispatchSeq,
                        WorkOrderStep::isDispatched)
                .ifPresent(
                        step -> {
                            LocalDateTime now = LocalDateTime.now();
                            step.startByEquipment(now);
                            findWorkOrderById(workOrderId).recordStepChange(now);
                        });
    }

    /** 설비의 완료 보고: 그 설비에서 이번 회차로 진행 중인 공정이면 실적(작업자 없음)을 남기고 양품 DONE, 불량 PENDING(다시 배정). 아니면 무시한다. */
    @Transactional
    public void reportStepCompleted(
            Long workOrderId,
            ProductionProcess process,
            String equipmentCode,
            StepResult result,
            String defectReason,
            Integer dispatchSeq) {
        reportableStep(
                        workOrderId,
                        process,
                        equipmentCode,
                        dispatchSeq,
                        s -> s.isInProgress() || s.isDispatched())
                .ifPresent(
                        step ->
                                finishReported(
                                        findWorkOrderById(workOrderId),
                                        step,
                                        result,
                                        defectReason));
    }

    /**
     * 설비 완료 보고 반영. 시작·완료는 큐가 달라 순서가 바뀔 수 있어, 아직 DISPATCHED 면 시작을 먼저 반영한다 (시작 시각 = 지금). 뒤늦게 온 시작 보고는
     * 공정이 이미 끝나 무시된다 (docs/specs/simulator.md).
     */
    private void finishReported(
            WorkOrder workOrder, WorkOrderStep step, StepResult result, String defectReason) {
        if (step.isDispatched()) {
            step.startByEquipment(LocalDateTime.now());
        }
        finishStep(workOrder, step, result, defectReason, null);
    }

    /** 시작 보고 없이 deadline 전에 배정된 공정 id, 오래된 순서로 limit 개. 배정 스케줄러가 바퀴마다 되돌린다. */
    @Transactional(readOnly = true)
    public List<Long> findStaleDispatchedStepIds(LocalDateTime deadline, int limit) {
        return findDispatchedBefore(deadline, limit).stream().map(WorkOrderStep::getId).toList();
    }

    /**
     * 시작 보고가 오지 않은 배정을 되돌린다: 공정 PENDING, 설비 비움 (고장 때 되돌리기와 같음, 실적 없음). 그사이 시작 보고가 왔으면 그대로 둔다. 다음
     * 바퀴에 다시 배정된다.
     */
    @Transactional
    public void withdrawStaleDispatch(Long stepId, LocalDateTime deadline) {
        WorkOrderStep step = findStep(stepId);
        if (step.isDispatchedBefore(deadline)) {
            withdraw(step, "시작 보고 없이 시간이 지나 배정을 되돌림");
        }
    }

    /**
     * 설비가 시작한 뒤 완료 보고 없이 deadline 이 지난 공정 id, 오래된 순서로 limit 개 (docs/specs/simulator-restart.md
     * "3").
     */
    @Transactional(readOnly = true)
    public List<Long> findStaleProgressStepIds(LocalDateTime deadline, int limit) {
        return findEquipmentRunStartedBefore(deadline, limit).stream()
                .map(WorkOrderStep::getId)
                .toList();
    }

    /**
     * 완료 보고가 오지 않은 설비 공정을 되돌린다 (시작 보고 시간 초과와 같은 처리). 그사이 완료됐거나 작업자 공정이면 그대로 둔다. 다시 배정하면 회차가 올라 늦은
     * 완료 보고는 무시된다.
     */
    @Transactional
    public void withdrawStaleProgress(Long stepId, LocalDateTime deadline) {
        WorkOrderStep step = findStep(stepId);
        if (step.isEquipmentRunStartedBefore(deadline)) {
            withdraw(step, "완료 보고 없이 시간이 지나 설비 공정을 되돌림");
        }
    }

    /** 공정을 시작 전으로 되돌리고 설비를 비운다 (실적 없음). 작업지시 변경을 기록해 동시 변경과 version 으로 부딪히게 한다. */
    private void withdraw(WorkOrderStep step, String reason) {
        log.info(
                "{}: workOrderId={} process={} equipment={}",
                reason,
                step.getWorkOrderId(),
                step.getProcess(),
                step.getEquipmentCode());
        findEquipment(step.getEquipmentCode()).release();
        step.interrupt();
        findWorkOrderById(step.getWorkOrderId()).recordStepChange(LocalDateTime.now());
    }

    /** 설비 보고를 반영할 공정. 작업지시가 없거나 끝났거나, 공정 상태·설비가 맞지 않으면 empty (늦거나 꼬인 보고, 수동 개입). */
    private Optional<WorkOrderStep> reportableStep(
            Long workOrderId,
            ProductionProcess process,
            String equipmentCode,
            Integer dispatchSeq,
            Predicate<WorkOrderStep> expected) {
        Optional<WorkOrder> workOrder = findWorkOrderIfExists(workOrderId);
        if (workOrder.isEmpty() || workOrder.get().isClosed()) {
            log.info("작업지시가 없거나 끝나 설비 보고 무시: workOrderId={}", workOrderId);
            return Optional.empty();
        }
        WorkOrderStep step = stepOf(findSteps(workOrderId), process);
        if (!expected.test(step)
                || !equipmentCode.equals(step.getEquipmentCode())
                || !step.isCurrentDispatch(dispatchSeq)) {
            log.info(
                    "공정 상태·설비·회차가 맞지 않아 설비 보고 무시: workOrderId={} process={} status={} equipment={} reported={} seq={} reportedSeq={}",
                    workOrderId,
                    process,
                    step.getStatus(),
                    step.getEquipmentCode(),
                    equipmentCode,
                    step.getDispatchSeq(),
                    dispatchSeq);
            return Optional.empty();
        }
        return Optional.of(step);
    }

    /**
     * 진행 중인 공정을 끝내고 실적을 남긴다. 양품이면 DONE, 불량이면 실적만 남기고 재작업(PENDING). 의장 양품이면 찍은 부품 LOT 을 이번 것으로 바꿔
     * 남긴다 (docs/specs/part-lot-scan.md). 의장 양품이 아닌데 찍은 LOT 이 있으면 SCAN_NOT_ALLOWED.
     */
    @Transactional
    public WorkOrderDetailResponse completeStep(
            Long workOrderId,
            ProductionProcess process,
            StepResult result,
            String defectReason,
            List<ScannedLotRequest> scannedLots,
            String workerId) {
        boolean assemblyGood = process == ProductionProcess.ASSEMBLY && result == StepResult.GOOD;
        if (!scannedLots.isEmpty() && !assemblyGood) {
            throw new CustomException(MesErrorCode.SCAN_NOT_ALLOWED);
        }
        WorkOrder workOrder = findOpenWorkOrder(workOrderId);
        List<WorkOrderStep> steps = findSteps(workOrderId);
        WorkOrderStep step = stepOf(steps, process);
        if (!step.isInProgress()) {
            throw invalidStepStatus(step);
        }
        finishStep(workOrder, step, result, defectReason, workerId);
        if (assemblyGood) {
            replaceScans(workOrderId, scannedLots, workerId);
        }
        flushOrConflict(workOrderId);
        return detail(workOrder, steps);
    }

    /** 작업지시의 찍은 LOT 을 이번 것으로 바꾼다 (재작업 뒤 다시 양품이면 마지막 것만 남는다). */
    private void replaceScans(
            Long workOrderId, List<ScannedLotRequest> scannedLots, String workerId) {
        workOrderPartScanRepository.deleteAllByWorkOrderId(workOrderId);
        workOrderPartScanRepository.flush();
        LocalDateTime now = LocalDateTime.now();
        workOrderPartScanRepository.saveAll(
                scannedLots.stream()
                        .map(
                                scan ->
                                        WorkOrderPartScan.record(
                                                workOrderId,
                                                scan.partId(),
                                                scan.lotNo(),
                                                workerId,
                                                now))
                        .toList());
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

    /**
     * 마지막 공정(검사)이 양품으로 끝났다: VIN 을 발행하고 작업지시를 완료한 뒤 production 에 알린다
     * (docs/specs/production-completion.md).
     */
    private void completeWorkOrder(WorkOrder workOrder, LocalDateTime now) {
        if (!Vin.fits(workOrder.getProductId(), workOrder.getId())) {
            log.error(
                    "VIN_RANGE_EXCEEDED workOrderId={} productId={}",
                    workOrder.getId(),
                    workOrder.getProductId());
            throw new CustomException(MesErrorCode.VIN_RANGE_EXCEEDED);
        }
        String vin = Vin.issue(workOrder.getProductId(), workOrder.getId(), now.getYear());
        workOrder.complete(vin, now);
        eventPublisher.publish(
                new VehicleCompleted(
                        workOrder.getProductionId(),
                        workOrder.getId(),
                        vin,
                        findScans(workOrder.getId()).stream()
                                .map(scan -> new ScannedLot(scan.getPartId(), scan.getLotNo()))
                                .toList()));
    }

    /** 의장 공정에서 찍은 LOT, 부품 id 순. */
    private List<WorkOrderPartScan> findScans(Long workOrderId) {
        return workOrderPartScanRepository.findAllByWorkOrderIdOrderByPartIdAsc(workOrderId);
    }

    /**
     * 설비가 고장·정지해 그 설비가 맡은 공정(배정·진행 중일 때만)을 시작 전으로 되돌린다 (EquipmentService 와 같은 트랜잭션). 작업지시 변경을 기록해
     * 같은 작업지시를 동시에 바꾸는 작업자 요청과 version 으로 부딪히게 한다. 작업지시 상태는 그대로다.
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
            throw new CustomException(MesErrorCode.STEP_OUT_OF_ORDER);
        }
    }

    /** 이 공정의 설비이고, 가동 중이고, 다른 공정을 맡고 있지 않아야 한다. 같은 설비를 동시에 잡으면 설비 version 충돌로 한쪽만 성공한다. */
    private void requireUsable(Equipment equipment, ProductionProcess process) {
        if (equipment.getProcess() != process) {
            throw new CustomException(MesErrorCode.EQUIPMENT_PROCESS_MISMATCH);
        }
        if (!equipment.isRunning()) {
            throw new CustomException(MesErrorCode.EQUIPMENT_NOT_RUNNING);
        }
        if (equipment.isOccupied()) {
            throw new CustomException(MesErrorCode.EQUIPMENT_BUSY);
        }
    }

    private static CustomException invalidStepStatus(WorkOrderStep step) {
        log.info(
                "공정 상태가 맞지 않아 거절: workOrderId={} process={} status={}",
                step.getWorkOrderId(),
                step.getProcess(),
                step.getStatus());
        return new CustomException(MesErrorCode.INVALID_STEP_STATUS);
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
                .orElseThrow(() -> new CustomException(MesErrorCode.WORK_ORDER_NOT_FOUND));
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
            throw new CustomException(MesErrorCode.WORK_ORDER_CLOSED);
        }
        return workOrder;
    }

    private WorkOrder findWorkOrderById(Long workOrderId) {
        return workOrderRepository
                .findById(workOrderId)
                .orElseThrow(() -> new CustomException(MesErrorCode.WORK_ORDER_NOT_FOUND));
    }

    private List<WorkOrder> findWorkOrdersAfter(WorkOrderStatus status, Long lastId, int limit) {
        return workOrderRepository.findAllByStatusAndIdGreaterThanOrderByIdAsc(
                status, lastId, Limit.of(limit));
    }

    /** 설비가 맡은 공정. 없으면 데이터가 꼬인 것이라 원인이 보이게 STEP_NOT_FOUND. */
    private WorkOrderStep findStep(Long stepId) {
        return workOrderStepRepository
                .findById(stepId)
                .orElseThrow(() -> new CustomException(MesErrorCode.STEP_NOT_FOUND));
    }

    private List<WorkOrderStep> findDispatchedBefore(LocalDateTime deadline, int limit) {
        return workOrderStepRepository.findAllByStatusAndDispatchedAtBeforeOrderByDispatchedAtAsc(
                StepStatus.DISPATCHED, deadline, Limit.of(limit));
    }

    private List<WorkOrderStep> findEquipmentRunStartedBefore(LocalDateTime deadline, int limit) {
        return workOrderStepRepository
                .findAllByStatusAndDispatchedAtIsNotNullAndStartedAtBeforeOrderByStartedAtAsc(
                        StepStatus.IN_PROGRESS, deadline, Limit.of(limit));
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

    private boolean hasWorkOrder(Long productionId) {
        return workOrderRepository.existsByProductionId(productionId);
    }

    private Optional<WorkOrder> findWorkOrderOfProduction(Long productionId) {
        return workOrderRepository.findByProductionId(productionId);
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
                .orElseThrow(() -> new CustomException(MesErrorCode.EQUIPMENT_NOT_FOUND));
    }

    /** 응답·이벤트를 만들기 전에 저장해 version 을 확인한다. 다른 요청이 먼저 바꿨으면 MES_CONFLICT. */
    private void flushOrConflict(Long workOrderId) {
        try {
            workOrderRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.info("다른 작업이 먼저 바꿔 거절: workOrderId={}", workOrderId);
            throw new CustomException(MesErrorCode.MES_CONFLICT);
        }
    }
}

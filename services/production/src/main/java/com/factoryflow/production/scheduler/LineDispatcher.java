package com.factoryflow.production.scheduler;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.config.LineProperties;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.production.service.WorkOrderService;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 로봇 공정 배정 한 바퀴 (docs/specs/production-work-order.md "3. 로봇 공정 자동 진행"). 공정마다 비어 있는 설비에 먼저 발행된 차부터
 * 배정한다. 차마다 따로 트랜잭션이라 한 차가 실패해도 나머지는 배정된다. 실패한 차는 이번 바퀴에서 빼고 다음 후보로 빈 설비를 채운다 (계속 실패하는 차가 뒤의 차를 막지
 * 않게). 실패한 차는 다음 바퀴에 다시 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineDispatcher {

    /** 설비 쪽 이유로 실패한 경우. 차는 그대로 두고 그 설비만 이번 바퀴에서 빼고, 같은 차로 다음 설비를 시도한다. */
    private static final Set<ProductionErrorCode> EQUIPMENT_ERRORS =
            Set.of(
                    ProductionErrorCode.EQUIPMENT_NOT_FOUND,
                    ProductionErrorCode.EQUIPMENT_PROCESS_MISMATCH,
                    ProductionErrorCode.EQUIPMENT_NOT_RUNNING,
                    ProductionErrorCode.EQUIPMENT_BUSY);

    /** 한 바퀴에 되돌리는 오래된 배정의 최대 수. 남은 것은 다음 바퀴에 본다. */
    private static final int STALE_DISPATCH_BATCH = 100;

    private final WorkOrderService workOrderService;
    private final LineProperties lineProperties;

    public void dispatchAll() {
        withdrawStaleDispatches();
        for (ProductionProcess process : ProductionProcess.values()) {
            if (process.isAutomated()) {
                dispatch(process);
            }
        }
    }

    /**
     * 시작 보고 없이 시간이 지난 배정과, 완료 보고 없이 시간이 지난 설비 공정을 되돌린다 (공정마다 따로 트랜잭션). 같은 바퀴의 배정에서 바로 다시 배정된다. 그사이
     * 보고가 와서 실패하면 다음 바퀴에 다시 본다.
     */
    private void withdrawStaleDispatches() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startDeadline = now.minus(lineProperties.startReportTimeout());
        for (Long stepId :
                workOrderService.findStaleDispatchedStepIds(startDeadline, STALE_DISPATCH_BATCH)) {
            withdrawQuietly(
                    stepId, id -> workOrderService.withdrawStaleDispatch(id, startDeadline));
        }
        LocalDateTime completionDeadline = now.minus(lineProperties.completionReportTimeout());
        for (Long stepId :
                workOrderService.findStaleProgressStepIds(
                        completionDeadline, STALE_DISPATCH_BATCH)) {
            withdrawQuietly(
                    stepId, id -> workOrderService.withdrawStaleProgress(id, completionDeadline));
        }
    }

    /** 공정마다 따로 트랜잭션으로 되돌린다. 그사이 보고가 와서 실패하면 다음 바퀴에 다시 본다. */
    private void withdrawQuietly(Long stepId, Consumer<Long> withdraw) {
        try {
            withdraw.accept(stepId);
        } catch (CustomException | OptimisticLockingFailureException e) {
            log.info("공정 되돌리기 건너뜀, 다음 바퀴에 다시 봄: stepId={} reason={}", stepId, e.getMessage());
        }
    }

    /** 빈 설비가 남아 있고 후보가 있는 동안 계속 배정한다. 차마다 설비가 쓰이거나 빠지거나 차가 빠지므로 끝난다. */
    private void dispatch(ProductionProcess process) {
        Deque<String> freeEquipmentCodes =
                new ArrayDeque<>(workOrderService.findFreeEquipmentCodes(process));
        Set<Long> skipped = new HashSet<>();
        while (!freeEquipmentCodes.isEmpty()) {
            List<Long> workOrderIds =
                    workOrderService.findDispatchableWorkOrderIds(
                            process, skipped, freeEquipmentCodes.size());
            if (workOrderIds.isEmpty()) {
                return;
            }
            for (Long workOrderId : workOrderIds) {
                if (freeEquipmentCodes.isEmpty()) {
                    return;
                }
                DispatchOutcome outcome =
                        tryEquipments(
                                freeEquipmentCodes, code -> attempt(workOrderId, process, code));
                if (outcome == DispatchOutcome.CAR_FAILED) {
                    skipped.add(workOrderId);
                }
            }
        }
    }

    /**
     * 차 하나를 빈 설비에 앞에서부터 시도한다. 설비 쪽 실패면 그 설비만 빼고 같은 차로 다음 설비를 시도해, 먼저 발행된 차가 뒤의 차에 밀리지 않게 한다. 배정되면 그
     * 설비를 쓰고, 차 쪽 실패면 설비는 다음 차를 위해 남긴다. 설비가 다 빠지면 차 잘못이 아니므로 EQUIPMENT_FAILED.
     */
    static DispatchOutcome tryEquipments(
            Deque<String> freeEquipmentCodes, Function<String, DispatchOutcome> attempt) {
        while (!freeEquipmentCodes.isEmpty()) {
            DispatchOutcome outcome = attempt.apply(freeEquipmentCodes.peek());
            if (outcome == DispatchOutcome.CAR_FAILED) {
                return outcome;
            }
            freeEquipmentCodes.pop();
            if (outcome == DispatchOutcome.DISPATCHED) {
                return outcome;
            }
        }
        return DispatchOutcome.EQUIPMENT_FAILED;
    }

    private DispatchOutcome attempt(
            Long workOrderId, ProductionProcess process, String equipmentCode) {
        try {
            workOrderService.dispatch(workOrderId, process, equipmentCode);
            return DispatchOutcome.DISPATCHED;
        } catch (CustomException e) {
            logSkipped(workOrderId, process, equipmentCode, e.getMessage());
            return EQUIPMENT_ERRORS.contains(e.getErrorCode())
                    ? DispatchOutcome.EQUIPMENT_FAILED
                    : DispatchOutcome.CAR_FAILED;
        } catch (OptimisticLockingFailureException e) {
            logSkipped(workOrderId, process, equipmentCode, e.getMessage());
            return DispatchOutcome.CAR_FAILED;
        }
    }

    private static void logSkipped(
            Long workOrderId, ProductionProcess process, String equipmentCode, String reason) {
        log.info(
                "배정 건너뜀, 다음 바퀴에 다시 봄: workOrderId={} process={} equipment={} reason={}",
                workOrderId,
                process,
                equipmentCode,
                reason);
    }
}

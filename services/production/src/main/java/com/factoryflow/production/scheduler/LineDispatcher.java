package com.factoryflow.production.scheduler;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.production.service.WorkOrderService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /** 설비 쪽 이유로 실패한 경우. 차는 그대로 두고 그 설비만 이번 바퀴에서 뺀다. */
    private static final Set<ProductionErrorCode> EQUIPMENT_ERRORS =
            Set.of(
                    ProductionErrorCode.EQUIPMENT_NOT_FOUND,
                    ProductionErrorCode.EQUIPMENT_PROCESS_MISMATCH,
                    ProductionErrorCode.EQUIPMENT_NOT_RUNNING,
                    ProductionErrorCode.EQUIPMENT_BUSY);

    private final WorkOrderService workOrderService;

    public void dispatchAll() {
        for (ProductionProcess process : ProductionProcess.values()) {
            if (process.isAutomated()) {
                dispatch(process);
            }
        }
    }

    /** 빈 설비가 남아 있고 후보가 있는 동안 계속 배정한다. 한 번 시도할 때마다 설비가 쓰이거나 빠지거나 차가 빠지므로 끝난다. */
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
                dispatchOne(workOrderId, process, freeEquipmentCodes, skipped);
            }
        }
    }

    /** 성공하면 그 설비를 쓰고, 설비 쪽 이유로 실패하면 그 설비를, 차 쪽 이유로 실패하면 그 차를 이번 바퀴에서 뺀다. */
    private void dispatchOne(
            Long workOrderId,
            ProductionProcess process,
            Deque<String> freeEquipmentCodes,
            Set<Long> skipped) {
        String equipmentCode = freeEquipmentCodes.peek();
        try {
            workOrderService.dispatch(workOrderId, process, equipmentCode);
            freeEquipmentCodes.pop();
        } catch (CustomException e) {
            logSkipped(workOrderId, process, equipmentCode, e.getMessage());
            if (EQUIPMENT_ERRORS.contains(e.getErrorCode())) {
                freeEquipmentCodes.pop();
            } else {
                skipped.add(workOrderId);
            }
        } catch (OptimisticLockingFailureException e) {
            logSkipped(workOrderId, process, equipmentCode, e.getMessage());
            skipped.add(workOrderId);
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

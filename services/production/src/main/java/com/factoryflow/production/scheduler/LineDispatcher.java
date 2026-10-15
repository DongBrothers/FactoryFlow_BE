package com.factoryflow.production.scheduler;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.service.WorkOrderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 로봇 공정 배정 한 바퀴 (docs/specs/production-work-order.md "3. 로봇 공정 자동 진행"). 공정마다 비어 있는 설비에 먼저 발행된 차부터
 * 배정한다. 차마다 따로 트랜잭션이라, 한 차가 그사이 바뀌어 실패해도 나머지는 배정되고 실패한 차는 다음 바퀴에 다시 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineDispatcher {

    private final WorkOrderService workOrderService;

    public void dispatchAll() {
        for (ProductionProcess process : ProductionProcess.values()) {
            if (process.isAutomated()) {
                dispatch(process);
            }
        }
    }

    private void dispatch(ProductionProcess process) {
        List<String> freeEquipmentCodes = workOrderService.findFreeEquipmentCodes(process);
        if (freeEquipmentCodes.isEmpty()) {
            return;
        }
        List<Long> workOrderIds =
                workOrderService.findDispatchableWorkOrderIds(process, freeEquipmentCodes.size());
        for (int i = 0; i < workOrderIds.size(); i++) {
            dispatchOne(workOrderIds.get(i), process, freeEquipmentCodes.get(i));
        }
    }

    private void dispatchOne(Long workOrderId, ProductionProcess process, String equipmentCode) {
        try {
            workOrderService.dispatch(workOrderId, process, equipmentCode);
        } catch (CustomException | OptimisticLockingFailureException e) {
            log.info(
                    "배정 건너뜀, 다음 바퀴에 다시 봄: workOrderId={} process={} equipment={} reason={}",
                    workOrderId,
                    process,
                    equipmentCode,
                    e.getMessage());
        }
    }
}

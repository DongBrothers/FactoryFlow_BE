package com.factoryflow.mes.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import com.factoryflow.mes.domain.ProductionProcess;

/** 공정 불량으로 차를 폐기했다. production 이 생산 건을 폐기하고 대체 차를 만든다 (docs/specs/defect-scrap.md). */
public record VehicleScrapped(
        Long productionId, Long workOrderId, ProductionProcess process, String defectReason)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "mes.vehicle-scrapped";
    }
}

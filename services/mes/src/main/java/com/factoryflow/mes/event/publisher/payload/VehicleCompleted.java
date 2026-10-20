package com.factoryflow.mes.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 검사 양품으로 차가 완성돼 VIN 을 발행했다. production 이 생산 건을 COMPLETED 로 바꾸고 production.completed 를 보낸다. */
public record VehicleCompleted(Long productionId, Long workOrderId, String vin)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "mes.vehicle-completed";
    }
}

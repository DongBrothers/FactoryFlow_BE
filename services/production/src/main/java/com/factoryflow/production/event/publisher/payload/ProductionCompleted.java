package com.factoryflow.production.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 검사 양품으로 차 한 대가 완성됐다. inventory 는 부품을 사용 처리하고 완성차 재고를 늘리고, order 는 차량을 COMPLETED 로 바꾼다. */
public record ProductionCompleted(Long orderId, Long productionId, Long productId, String vin)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "production.completed";
    }
}

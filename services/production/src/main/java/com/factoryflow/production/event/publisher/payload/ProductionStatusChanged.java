package com.factoryflow.production.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import com.factoryflow.production.domain.ProductionStatus;

/** 생산 건 상태가 바뀌었다. order 가 주문 품목 상태를 갱신하는 데 쓴다. */
public record ProductionStatusChanged(
        Long orderId, Long productionId, Long productId, ProductionStatus status)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "production.status-changed";
    }
}

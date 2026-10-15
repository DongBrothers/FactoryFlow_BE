package com.factoryflow.production.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 생산 건이 취소됐다. inventory 가 그 생산 건의 예약을 해제한다. */
public record ProductionCancelled(Long orderId, Long productionId) implements DomainEvent {

    @Override
    public String eventName() {
        return "production.cancelled";
    }
}

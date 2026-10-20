package com.factoryflow.production.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 생산 담당이 이 차의 작업지시를 발행했다. mes 가 받아 작업지시와 공정 5개를 만든다 (docs/specs/mes-split.md). */
public record ProductionReleased(Long productionId, Long orderId, Long productId)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "production.released";
    }
}

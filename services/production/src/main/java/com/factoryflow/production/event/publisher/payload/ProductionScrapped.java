package com.factoryflow.production.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 생산 건을 폐기했다. inventory 가 그 차가 잡아 둔 부품을 폐기 소모한다 (docs/specs/defect-scrap.md). */
public record ProductionScrapped(Long orderId, Long productionId) implements DomainEvent {

    @Override
    public String eventName() {
        return "production.scrapped";
    }
}

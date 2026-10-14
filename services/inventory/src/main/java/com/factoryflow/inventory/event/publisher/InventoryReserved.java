package com.factoryflow.inventory.event.publisher;

import com.factoryflow.common.event.DomainEvent;

/** 생산 건의 부품이 모두 예약됐다. */
public record InventoryReserved(Long productionId) implements DomainEvent {

    @Override
    public String eventName() {
        return "inventory.reserved";
    }
}

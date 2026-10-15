package com.factoryflow.inventory.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import java.util.List;

/** 생산 건의 부품 중 일부가 입고를 기다린다. shortages 는 그 생산 건의 WAITING 줄이다. */
public record ReservationDeferred(Long productionId, List<ReservationShortage> shortages)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "inventory.reservation-pending";
    }
}

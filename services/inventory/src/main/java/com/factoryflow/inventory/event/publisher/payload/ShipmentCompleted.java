package com.factoryflow.inventory.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 완성차 한 대가 출고됐다. productId 는 order 가 아직 모르는 차량의 품목을 찾는 데 쓴다. */
public record ShipmentCompleted(Long orderId, Long productionId, Long productId, String vin)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "shipment.completed";
    }
}

package com.factoryflow.order.event.listener.payload;

/** 받은 shipment.completed 의 payload (docs/specs/events.md). */
public record ShipmentCompleted(Long orderId, Long productionId, Long productId, String vin) {}

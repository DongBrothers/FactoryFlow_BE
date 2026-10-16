package com.factoryflow.inventory.event.listener.payload;

/** 받은 production.completed 의 payload (docs/specs/events.md). */
public record ProductionCompleted(Long orderId, Long productionId, Long productId, String vin) {}

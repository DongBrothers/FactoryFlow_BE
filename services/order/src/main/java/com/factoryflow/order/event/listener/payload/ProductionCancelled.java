package com.factoryflow.order.event.listener.payload;

/** 받은 production.cancelled 의 payload (docs/specs/events.md). */
public record ProductionCancelled(Long orderId, Long productionId) {}

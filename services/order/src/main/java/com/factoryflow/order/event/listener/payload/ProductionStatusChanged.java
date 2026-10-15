package com.factoryflow.order.event.listener.payload;

/**
 * 받은 production.status-changed 의 payload (docs/specs/events.md). status 는 production 의 생산 건 상태
 * 이름이다.
 */
public record ProductionStatusChanged(
        Long orderId, Long productionId, Long productId, String status) {}

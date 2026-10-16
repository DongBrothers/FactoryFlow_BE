package com.factoryflow.order.event.listener.payload;

/** 받은 production.cancel-rejected 의 payload. status 는 거절 때의 생산 건 상태로, 차량을 이 상태로 되돌린다. */
public record ProductionCancelRejected(
        Long orderId, Long productionId, String reason, String status) {}

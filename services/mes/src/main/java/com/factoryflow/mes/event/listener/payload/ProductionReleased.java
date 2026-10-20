package com.factoryflow.mes.event.listener.payload;

/** 받은 production.released 의 payload (docs/specs/mes-split.md). 생산 담당이 이 차의 작업지시를 발행했다. */
public record ProductionReleased(Long productionId, Long orderId, Long productId) {}

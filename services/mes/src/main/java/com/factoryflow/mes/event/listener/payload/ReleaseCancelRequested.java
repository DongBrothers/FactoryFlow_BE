package com.factoryflow.mes.event.listener.payload;

/**
 * 받은 production.release-cancel-requested 의 payload (docs/specs/mes-split.md). 발행한 차의 주문이 취소 요청됐다.
 */
public record ReleaseCancelRequested(Long orderId, Long productionId) {}

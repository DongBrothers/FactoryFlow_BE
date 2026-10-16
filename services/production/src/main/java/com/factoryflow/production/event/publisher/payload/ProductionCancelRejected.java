package com.factoryflow.production.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import com.factoryflow.production.domain.ProductionStatus;

/**
 * 생산 건 취소를 거절했다 (이미 프레스가 배정됨). status 는 지금 생산 건 상태로, order 가 취소 요청 중이던 차량을 이 상태로 되돌린다
 * (docs/specs/order-cancel.md).
 */
public record ProductionCancelRejected(
        Long orderId, Long productionId, String reason, ProductionStatus status)
        implements DomainEvent {

    /** 프레스가 이미 배정돼 생산 순서가 확정됨. */
    public static final String ALREADY_STARTED = "ALREADY_STARTED";

    @Override
    public String eventName() {
        return "production.cancel-rejected";
    }
}

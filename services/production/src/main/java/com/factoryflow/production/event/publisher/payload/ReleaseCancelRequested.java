package com.factoryflow.production.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 작업지시를 발행한 차의 주문이 취소 요청됐다. 시작 여부를 아는 mes 가 취소하거나 거절한다 (docs/specs/mes-split.md). */
public record ReleaseCancelRequested(Long orderId, Long productionId) implements DomainEvent {

    @Override
    public String eventName() {
        return "production.release-cancel-requested";
    }
}

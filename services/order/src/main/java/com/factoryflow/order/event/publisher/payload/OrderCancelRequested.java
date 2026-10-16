package com.factoryflow.order.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 고객이 주문 전체 취소를 요청했다. production 이 차량마다 취소·거절로 답한다. */
public record OrderCancelRequested(Long orderId) implements DomainEvent {

    @Override
    public String eventName() {
        return "order.cancel-requested";
    }
}

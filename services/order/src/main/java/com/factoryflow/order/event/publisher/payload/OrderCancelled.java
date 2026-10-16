package com.factoryflow.order.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 취소 요청의 답이 다 왔고, 이번 요청으로 취소된 차량이 하나라도 있다. */
public record OrderCancelled(Long orderId) implements DomainEvent {

    @Override
    public String eventName() {
        return "order.cancelled";
    }
}

package com.factoryflow.production.event.listener.payload;

/** 받은 order.cancel-requested 의 payload (docs/specs/events.md). 주문 전체 취소 요청이다. */
public record OrderCancelRequested(Long orderId) {}

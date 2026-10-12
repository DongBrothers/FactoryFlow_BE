package com.factoryflow.order.domain;

/** 주문과 주문 품목의 상태. 주문 상태는 품목 상태의 요약이다 (docs/specs/saga-order-fulfillment.md). */
public enum OrderStatus {
    RECEIVED,
    WAITING_MATERIAL,
    IN_PRODUCTION,
    COMPLETED,
    CANCEL_REQUESTED,
    CANCELLED,
    FAILED
}

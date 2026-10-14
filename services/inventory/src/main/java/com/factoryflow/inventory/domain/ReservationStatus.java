package com.factoryflow.inventory.domain;

/** 부품 예약 줄의 상태 (docs/specs/saga-order-fulfillment.md). */
public enum ReservationStatus {
    /** 재고가 모자라거나 앞에 기다리는 줄이 있어 입고를 기다린다. 재고는 건드리지 않았다 */
    WAITING,
    /** 생산 건 몫으로 재고를 잡았다 (available −, reserved +) */
    RESERVED,
    /** 생산 시작 전 취소로 잡은 재고를 풀었다 */
    RELEASED,
    /** 생산에 썼다 */
    CONSUMED
}

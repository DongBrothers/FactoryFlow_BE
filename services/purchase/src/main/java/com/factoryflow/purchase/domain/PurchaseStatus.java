package com.factoryflow.purchase.domain;

/** 구매 건 상태. ORDERED(발주) → RECEIVED(입고). 만들 때 바로 발주다 (docs/specs/purchase-manual.md). */
public enum PurchaseStatus {
    /** 공급업체에 발주함 */
    ORDERED,
    /** 입고됨. purchase.received 로 inventory 재고가 늘어난다 */
    RECEIVED,
    /** 취소됨. 자동 요청 시절 발주되지 않은 건을 V4 에서 이 상태로 바꿨다 */
    CANCELLED
}

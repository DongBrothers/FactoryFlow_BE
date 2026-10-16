package com.factoryflow.purchase.domain;

/** 구매 건 상태. REQUESTED(요청) → ORDERED(발주) → RECEIVED(입고). CANCELLED 는 이후 기능. */
public enum PurchaseStatus {
    /** 요청됨. 같은 부품의 부족이 더 오면 수량을 합친다 */
    REQUESTED,
    /** 공급업체에 발주함. 수량을 더 바꾸지 않는다 */
    ORDERED,
    /** 입고됨. purchase.received 로 inventory 재고가 늘어난다 */
    RECEIVED,
    /** 취소됨 (이후 기능) */
    CANCELLED
}

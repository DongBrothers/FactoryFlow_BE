package com.factoryflow.purchase.domain;

/** 구매 건이 생긴 곳. */
public enum PurchaseSource {
    /** 담당자가 직접 넣음 (이후 기능) */
    MANUAL,
    /** 재고 부족(inventory.shortage)으로 자동 요청 */
    AUTO
}

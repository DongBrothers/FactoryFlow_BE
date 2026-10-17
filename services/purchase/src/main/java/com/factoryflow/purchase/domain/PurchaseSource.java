package com.factoryflow.purchase.domain;

/** 구매 건이 생긴 곳. */
public enum PurchaseSource {
    /** 구매 담당이 직접 발주함 */
    MANUAL,
    /** 재고 부족 자동 요청 (없앤 기능. 그때 만든 구매 건에만 남아 있다) */
    AUTO
}

package com.factoryflow.inventory.domain;

/** 재고 이력이 가리키는 원인. ref_id 는 그 원인의 id 다. */
public enum StockHistoryRefType {
    PRODUCTION,
    PURCHASE
}

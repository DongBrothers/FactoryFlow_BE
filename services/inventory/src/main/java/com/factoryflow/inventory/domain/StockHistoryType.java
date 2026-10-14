package com.factoryflow.inventory.domain;

/** 재고 이력 종류. qty 는 항상 양수이고 증감 방향은 종류로 정한다. */
public enum StockHistoryType {
    /** available → reserved */
    RESERVE,
    /** reserved → available */
    RELEASE,
    /** 입고: available + */
    RECEIVE,
    /** 생산에 사용: reserved − */
    CONSUME
}

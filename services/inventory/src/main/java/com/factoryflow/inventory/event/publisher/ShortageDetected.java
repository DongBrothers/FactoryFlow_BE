package com.factoryflow.inventory.event.publisher;

import com.factoryflow.common.event.DomainEvent;

/** 새 WAITING 줄 때문에 부품 부족이 shortQty 만큼 늘었다. purchase 가 자동 구매에 쓴다. */
public record ShortageDetected(Long partId, String partCode, String partName, int shortQty)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "inventory.shortage";
    }
}

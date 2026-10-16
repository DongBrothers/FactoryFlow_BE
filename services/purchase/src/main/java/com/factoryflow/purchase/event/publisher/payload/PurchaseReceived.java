package com.factoryflow.purchase.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 구매 건이 입고됐다. inventory 가 재고를 늘리고 기다리던 예약 줄을 채운다. */
public record PurchaseReceived(Long purchaseId, Long partId, int qty) implements DomainEvent {

    @Override
    public String eventName() {
        return "purchase.received";
    }
}

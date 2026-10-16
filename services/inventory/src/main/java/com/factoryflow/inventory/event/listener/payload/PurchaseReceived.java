package com.factoryflow.inventory.event.listener.payload;

/** 받은 purchase.received 의 payload (docs/specs/events.md). */
public record PurchaseReceived(Long purchaseId, Long partId, int qty) {}

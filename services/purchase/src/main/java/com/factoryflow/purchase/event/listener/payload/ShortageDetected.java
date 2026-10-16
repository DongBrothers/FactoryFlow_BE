package com.factoryflow.purchase.event.listener.payload;

/** 받은 inventory.shortage 의 payload (docs/specs/events.md). shortQty 는 새로 늘어난 부족분이다. */
public record ShortageDetected(Long partId, String partCode, String partName, int shortQty) {}

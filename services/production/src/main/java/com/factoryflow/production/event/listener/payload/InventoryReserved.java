package com.factoryflow.production.event.listener.payload;

/** 받은 inventory.reserved 의 payload (docs/specs/events.md). */
public record InventoryReserved(Long productionId) {}

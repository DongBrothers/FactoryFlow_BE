package com.factoryflow.inventory.event.publisher.payload;

public record ReservationShortage(Long partId, int shortQty) {}

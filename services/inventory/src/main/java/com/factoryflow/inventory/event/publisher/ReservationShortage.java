package com.factoryflow.inventory.event.publisher;

public record ReservationShortage(Long partId, int shortQty) {}

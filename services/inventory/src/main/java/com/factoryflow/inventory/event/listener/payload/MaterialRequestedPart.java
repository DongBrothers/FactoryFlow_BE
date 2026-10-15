package com.factoryflow.inventory.event.listener.payload;

public record MaterialRequestedPart(Long partId, int qty) {}

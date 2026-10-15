package com.factoryflow.production.event.listener.payload;

public record OrderCreatedProduct(Long productId, int qty) {}

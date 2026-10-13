package com.factoryflow.production.event.listener;

public record OrderCreatedProduct(Long productId, int qty) {}

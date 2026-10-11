package com.factoryflow.order.event.publisher;

public record OrderCreatedProduct(Long productId, int qty) {}

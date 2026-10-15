package com.factoryflow.order.event.publisher.payload;

public record OrderCreatedProduct(Long productId, int qty) {}

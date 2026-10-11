package com.factoryflow.order.event.publisher;

/** order.created 의 products 항목 (docs/specs/events.md). */
public record OrderCreatedProduct(Long productId, int qty) {}

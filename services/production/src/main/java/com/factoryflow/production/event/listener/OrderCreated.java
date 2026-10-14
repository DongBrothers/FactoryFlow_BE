package com.factoryflow.production.event.listener;

import java.util.List;

/** 받은 order.created 의 payload (docs/specs/events.md). */
public record OrderCreated(Long orderId, List<OrderCreatedProduct> products) {}

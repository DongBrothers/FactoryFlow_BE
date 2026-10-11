package com.factoryflow.order.event.publisher;

import com.factoryflow.common.event.DomainEvent;
import java.util.List;

/** order.created (docs/specs/events.md). */
public record OrderCreated(Long orderId, List<OrderCreatedProduct> products)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "order.created";
    }
}

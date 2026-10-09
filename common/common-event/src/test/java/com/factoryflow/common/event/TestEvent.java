package com.factoryflow.common.event;

record TestEvent(long orderId) implements DomainEvent {

    static final String NAME = "test.happened";

    @Override
    public String eventName() {
        return NAME;
    }
}

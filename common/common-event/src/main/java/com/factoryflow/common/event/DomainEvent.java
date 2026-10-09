package com.factoryflow.common.event;

/** 서비스가 발행하는 이벤트 payload. 이름은 docs/specs/events.md 의 이벤트 이름과 같아야 한다 (예: order.created). */
public interface DomainEvent {

    String eventName();

    default int version() {
        return 1;
    }
}

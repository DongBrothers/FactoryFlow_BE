package com.factoryflow.simulator.event.publisher.payload;

/**
 * simulator 가 보내는 이벤트 payload. 이벤트 이름(라우팅 키)을 payload 가 정한다 (common-event 의 DomainEvent 와 같은 방식).
 */
public interface EquipmentEvent {

    String eventName();
}

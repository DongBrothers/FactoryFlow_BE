package com.factoryflow.mes.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 작업지시의 첫 공정(프레스)을 시작했다. production 이 생산 건을 IN_PROGRESS 로 바꾸고, 이후에는 취소할 수 없다. */
public record ProductionStarted(Long productionId, Long workOrderId) implements DomainEvent {

    @Override
    public String eventName() {
        return "mes.production-started";
    }
}

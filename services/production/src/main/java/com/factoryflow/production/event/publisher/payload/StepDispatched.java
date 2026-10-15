package com.factoryflow.production.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import com.factoryflow.production.domain.ProductionProcess;

/** 로봇 공정을 설비에 배정했다. 설비(simulator)가 받아 작업하고 시작·완료를 보고한다. */
public record StepDispatched(Long workOrderId, ProductionProcess process, String equipmentCode)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "production.step-dispatched";
    }
}

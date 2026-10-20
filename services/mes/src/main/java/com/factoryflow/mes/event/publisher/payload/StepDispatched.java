package com.factoryflow.mes.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import com.factoryflow.mes.domain.ProductionProcess;

/** 로봇 공정을 설비에 배정했다. 설비(simulator)가 받아 작업하고 시작·완료를 보고한다. dispatchSeq 는 배정 회차로, 설비가 보고에 그대로 돌려준다. */
public record StepDispatched(
        Long workOrderId, ProductionProcess process, String equipmentCode, int dispatchSeq)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "mes.step-dispatched";
    }
}

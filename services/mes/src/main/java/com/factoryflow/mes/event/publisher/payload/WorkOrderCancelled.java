package com.factoryflow.mes.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;

/** 취소 요청을 받아 첫 공정 시작 전의 작업지시를 취소했다. production 이 생산 건을 취소하고 보상(예약 해제)을 시작한다. */
public record WorkOrderCancelled(Long productionId) implements DomainEvent {

    @Override
    public String eventName() {
        return "mes.work-order-cancelled";
    }
}

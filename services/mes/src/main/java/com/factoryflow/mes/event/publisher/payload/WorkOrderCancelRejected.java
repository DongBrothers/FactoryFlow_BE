package com.factoryflow.mes.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import com.factoryflow.mes.domain.WorkOrderStatus;

/** 첫 공정을 이미 시작해 취소를 거절했다. status 는 지금 작업지시 상태 (IN_PROGRESS, COMPLETED). */
public record WorkOrderCancelRejected(Long productionId, WorkOrderStatus status)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "mes.work-order-cancel-rejected";
    }
}

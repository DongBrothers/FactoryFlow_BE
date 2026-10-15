package com.factoryflow.order.domain;

import java.util.Collection;
import java.util.List;

/** 주문과 주문 품목의 상태. 주문 상태는 품목 상태의 요약이다 (docs/specs/saga-order-fulfillment.md). */
public enum OrderStatus {
    /** 접수. 생산 건이 아직 없거나 PLANNED 다. */
    RECEIVED,
    /** 부품 대기. 생산 건이 WAITING_MATERIAL 이다 (재고 부족, 불량 재생산 포함). */
    WAITING_MATERIAL,
    /** 생산 중. 생산 건이 READY 또는 IN_PROGRESS 다. */
    IN_PRODUCTION,
    /** 생산 완료. 끝난 상태라 이후 상태 변경 이벤트는 무시한다. */
    COMPLETED,
    /** 취소 요청. 고객이 취소를 요청했고 production 의 cancelled / cancel-rejected 를 기다린다. */
    CANCEL_REQUESTED,
    /** 취소됨. 끝난 상태라 이후 상태 변경 이벤트는 무시한다. */
    CANCELLED,
    /** 실패. 주문 이행 흐름에서는 쓰지 않는다 (입구 검증과 불량 재생산으로 처리). */
    FAILED;

    /**
     * 품목 상태로 주문 상태를 요약한다. CANCELLED 품목은 빼고 남은 품목으로, 위에서부터 먼저 맞는 것을 쓴다
     * (docs/specs/saga-order-fulfillment.md "customer_order.status 요약").
     */
    public static OrderStatus summarize(Collection<OrderStatus> itemStatuses) {
        List<OrderStatus> remaining = itemStatuses.stream().filter(s -> s != CANCELLED).toList();
        if (remaining.isEmpty()) {
            return CANCELLED;
        }
        if (remaining.contains(CANCEL_REQUESTED)) {
            return CANCEL_REQUESTED;
        }
        if (remaining.stream().allMatch(s -> s == COMPLETED)) {
            return COMPLETED;
        }
        if (remaining.contains(IN_PRODUCTION) || remaining.contains(COMPLETED)) {
            return IN_PRODUCTION;
        }
        if (remaining.contains(WAITING_MATERIAL)) {
            return WAITING_MATERIAL;
        }
        return RECEIVED;
    }
}

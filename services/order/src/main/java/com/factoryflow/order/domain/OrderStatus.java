package com.factoryflow.order.domain;

import java.util.Collection;
import java.util.List;

/**
 * 차량(생산 건), 주문 품목, 주문의 상태. 품목 상태는 그 품목 차량들의 요약이고, 주문 상태는 품목들의 요약이다
 * (docs/specs/saga-order-fulfillment.md).
 */
public enum OrderStatus {
    /** 접수. 생산 건이 아직 없거나 PLANNED 다. */
    RECEIVED,
    /** 부품 대기. 생산 건이 WAITING_MATERIAL 이다. */
    WAITING_MATERIAL,
    /** 생산 중. 생산 건이 READY(작업지시 발행) 또는 IN_PROGRESS 다. */
    IN_PRODUCTION,
    /** 생산 완료 (VIN 발행). 끝난 상태라 이후 상태 변경 이벤트는 무시한다. */
    COMPLETED,
    /** 출고 완료. 끝난 상태다. */
    SHIPPED,
    /** 취소 요청. 고객이 취소를 요청했고 production 의 cancelled / cancel-rejected 를 기다린다. */
    CANCEL_REQUESTED,
    /** 취소됨. 끝난 상태라 이후 상태 변경 이벤트는 무시한다. */
    CANCELLED,
    /** 설비 고장으로 생산 실패. 끝난 상태라 이후 상태 변경 이벤트는 무시한다. */
    FAILED;

    /** 더 바뀌지 않는 상태. 늦게 온 이벤트는 무시한다. */
    public boolean isFinished() {
        return this == COMPLETED || this == SHIPPED || this == CANCELLED || this == FAILED;
    }

    /**
     * 차량 상태로 품목 상태를, 품목 상태로 주문 상태를 요약한다. CANCELLED 는 빼고 남은 것으로, 위에서부터 먼저 맞는 것을 쓴다
     * (docs/specs/saga-order-fulfillment.md "요약").
     */
    public static OrderStatus summarize(Collection<OrderStatus> statuses) {
        List<OrderStatus> remaining = statuses.stream().filter(s -> s != CANCELLED).toList();
        if (remaining.isEmpty()) {
            return CANCELLED;
        }
        if (remaining.contains(CANCEL_REQUESTED)) {
            return CANCEL_REQUESTED;
        }
        List<OrderStatus> alive = remaining.stream().filter(s -> s != FAILED).toList();
        if (alive.isEmpty()) {
            return FAILED;
        }
        return summarizeAlive(alive);
    }

    /** 취소·실패를 뺀 상태들의 요약. */
    private static OrderStatus summarizeAlive(List<OrderStatus> alive) {
        if (alive.stream().allMatch(s -> s == SHIPPED)) {
            return SHIPPED;
        }
        if (alive.stream().allMatch(s -> s == COMPLETED || s == SHIPPED)) {
            return COMPLETED;
        }
        if (alive.stream().anyMatch(s -> s == IN_PRODUCTION || s == COMPLETED || s == SHIPPED)) {
            return IN_PRODUCTION;
        }
        if (alive.contains(WAITING_MATERIAL)) {
            return WAITING_MATERIAL;
        }
        return RECEIVED;
    }
}

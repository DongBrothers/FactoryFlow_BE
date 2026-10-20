package com.factoryflow.production.event.listener.payload;

/**
 * 받은 mes.work-order-cancel-rejected 의 payload (docs/specs/mes-split.md). status 는 작업지시 상태
 * (IN_PROGRESS, COMPLETED).
 */
public record MesWorkOrderCancelRejected(Long productionId, String status) {}

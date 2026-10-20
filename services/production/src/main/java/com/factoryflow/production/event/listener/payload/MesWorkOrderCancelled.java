package com.factoryflow.production.event.listener.payload;

/** 받은 mes.work-order-cancelled 의 payload (docs/specs/mes-split.md). 발행한 차를 첫 공정 전에 취소했다. */
public record MesWorkOrderCancelled(Long productionId) {}

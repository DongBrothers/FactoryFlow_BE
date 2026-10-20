package com.factoryflow.production.event.listener.payload;

/** 받은 mes.production-started 의 payload (docs/specs/mes-split.md). 첫 공정(프레스)을 시작했다. */
public record MesProductionStarted(Long productionId, Long workOrderId) {}

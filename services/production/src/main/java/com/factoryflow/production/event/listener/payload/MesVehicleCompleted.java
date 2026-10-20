package com.factoryflow.production.event.listener.payload;

/** 받은 mes.vehicle-completed 의 payload (docs/specs/mes-split.md). 검사 양품으로 차가 완성돼 VIN 을 발행했다. */
public record MesVehicleCompleted(Long productionId, Long workOrderId, String vin) {}

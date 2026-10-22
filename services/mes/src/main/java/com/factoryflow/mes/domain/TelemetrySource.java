package com.factoryflow.mes.domain;

/** 센서값 한 개가 나온 곳: 설비, 작업지시, 공정, 배정 회차. 시계열 컬렉션의 metaField 다. */
public record TelemetrySource(
        String equipmentCode, Long workOrderId, ProductionProcess process, Integer dispatchSeq) {}

package com.factoryflow.simulator.domain;

/** 설비가 맡은 작업 하나 (배정 받은 공정). traceId 는 배정 메시지의 것을 보고에 이어 쓴다. */
public record Job(Long workOrderId, LineProcess process, String traceId) {}

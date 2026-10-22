package com.factoryflow.mes.event.listener.payload;

import java.time.Instant;
import java.util.Map;

/** 받은 equipment.step-telemetry 의 센서값 한 개: 잰 시각과 항목별 값. */
public record TelemetrySample(Instant at, Map<String, Double> values) {}

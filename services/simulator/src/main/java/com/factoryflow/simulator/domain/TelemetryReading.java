package com.factoryflow.simulator.domain;

import java.time.Instant;
import java.util.Map;

/** 설비가 한 번 잰 값: 시각과 항목별 값. */
public record TelemetryReading(Instant at, Map<String, Double> values) {}

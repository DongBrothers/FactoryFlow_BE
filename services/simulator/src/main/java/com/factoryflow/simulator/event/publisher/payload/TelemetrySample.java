package com.factoryflow.simulator.event.publisher.payload;

import java.time.Instant;
import java.util.Map;

/** equipment.step-telemetry 의 센서값 한 개. */
public record TelemetrySample(Instant at, Map<String, Double> values) {}

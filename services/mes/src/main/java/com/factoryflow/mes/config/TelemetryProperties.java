package com.factoryflow.mes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 센서값 메모리 상한 (docs/specs/process-telemetry.md "메모리 상한").
 *
 * @param maxSamplesPerMessage 메시지 하나의 센서값 수 상한. 넘으면 저장하지 않고 재시도 후 DLQ 로 보낸다 (비정상 메시지)
 * @param maxQuerySamples 조회 한 번에 읽는 센서값 수 상한. 넘으면 앞에서부터 이만큼만 주고 truncated 로 알린다
 */
@ConfigurationProperties(prefix = "factoryflow.telemetry")
public record TelemetryProperties(
        @DefaultValue("300") int maxSamplesPerMessage, @DefaultValue("5000") int maxQuerySamples) {}

package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.StepTelemetry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Schema(description = "센서값 한 개")
public record TelemetrySampleResponse(
        @Schema(description = "잰 시각", example = "2026-10-21T10:00:00.2") LocalDateTime measuredAt,
        @Schema(description = "항목별 값", example = "{\"pressureTon\": 801.2, \"strokeMm\": 299.8}")
                Map<String, Double> values) {

    public static TelemetrySampleResponse from(StepTelemetry telemetry) {
        return new TelemetrySampleResponse(
                LocalDateTime.ofInstant(telemetry.getMeasuredAt(), ZoneId.systemDefault()),
                telemetry.getValues());
    }
}

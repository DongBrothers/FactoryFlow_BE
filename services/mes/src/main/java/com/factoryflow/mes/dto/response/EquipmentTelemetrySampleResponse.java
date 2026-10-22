package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.StepTelemetry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Schema(description = "설비 센서값 한 개와 그때 하던 작업")
public record EquipmentTelemetrySampleResponse(
        @Schema(description = "잰 시각", example = "2026-10-21T10:00:00.2") LocalDateTime measuredAt,
        @Schema(description = "작업지시 번호", example = "12") Long workOrderId,
        @Schema(description = "배정 회차", example = "1") Integer dispatchSeq,
        @Schema(description = "항목별 값", example = "{\"pressureTon\": 801.2, \"strokeMm\": 299.8}")
                Map<String, Double> values) {

    public static EquipmentTelemetrySampleResponse from(StepTelemetry telemetry) {
        return new EquipmentTelemetrySampleResponse(
                LocalDateTime.ofInstant(telemetry.getMeasuredAt(), ZoneId.systemDefault()),
                telemetry.getMeta().workOrderId(),
                telemetry.getMeta().dispatchSeq(),
                telemetry.getValues());
    }
}

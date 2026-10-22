package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.TelemetryParameter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "센서 항목 하나와 정상 범위 (화면 기준선)")
public record TelemetryLimitResponse(
        @Schema(description = "항목 이름", example = "pressureTon") String name,
        @Schema(description = "단위", example = "ton") String unit,
        @Schema(description = "정상 범위 하한", example = "780") double lower,
        @Schema(description = "정상 범위 상한", example = "820") double upper) {

    public static TelemetryLimitResponse from(TelemetryParameter parameter) {
        return new TelemetryLimitResponse(
                parameter.key(), parameter.unit(), parameter.lower(), parameter.upper());
    }
}

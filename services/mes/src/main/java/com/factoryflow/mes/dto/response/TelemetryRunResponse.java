package com.factoryflow.mes.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "공정을 한 번 한 동안(배정 회차 하나)의 센서값")
public record TelemetryRunResponse(
        @Schema(description = "배정 회차. 시간 초과·설비 고장으로 다시 배정하면 늘어난다", example = "1")
                Integer dispatchSeq,
        @Schema(description = "설비 코드", example = "PRESS-01") String equipmentCode,
        @Schema(description = "센서값, 시간 순") List<TelemetrySampleResponse> samples) {

    public static TelemetryRunResponse of(
            Integer dispatchSeq, String equipmentCode, List<TelemetrySampleResponse> samples) {
        return new TelemetryRunResponse(dispatchSeq, equipmentCode, samples);
    }
}

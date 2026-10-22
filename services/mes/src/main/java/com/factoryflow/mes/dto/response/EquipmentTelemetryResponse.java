package com.factoryflow.mes.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "설비 하나의 시간 범위 센서값")
public record EquipmentTelemetryResponse(
        @Schema(description = "설비 코드", example = "PRESS-01") String equipmentCode,
        @Schema(description = "설비 공정의 센서 항목과 정상 범위") List<TelemetryLimitResponse> limits,
        @Schema(description = "센서값, 시간 순") List<EquipmentTelemetrySampleResponse> samples,
        @Schema(description = "조회 상한을 넘어 앞에서부터 일부만 줬는지. 그러면 범위를 좁혀 다시 본다", example = "false")
                boolean truncated) {

    public static EquipmentTelemetryResponse of(
            String equipmentCode,
            List<TelemetryLimitResponse> limits,
            List<EquipmentTelemetrySampleResponse> samples,
            boolean truncated) {
        return new EquipmentTelemetryResponse(equipmentCode, limits, samples, truncated);
    }
}

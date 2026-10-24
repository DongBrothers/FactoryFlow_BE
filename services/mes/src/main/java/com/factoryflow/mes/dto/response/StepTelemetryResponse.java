package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.ProductionProcess;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "작업지시 한 공정의 센서값 (다시 배정했으면 회차별로)")
public record StepTelemetryResponse(
        @Schema(description = "작업지시 번호", example = "12") Long workOrderId,
        @Schema(description = "공정", example = "PRESS") ProductionProcess process,
        @Schema(description = "공정의 센서 항목과 정상 범위") List<TelemetryLimitResponse> limits,
        @Schema(description = "회차별 센서값, 회차 순. 값이 없으면 빈 목록") List<TelemetryRunResponse> runs,
        @Schema(description = "조회 상한을 넘어 앞에서부터 일부만 줬는지", example = "false") boolean truncated) {

    public static StepTelemetryResponse of(
            Long workOrderId,
            ProductionProcess process,
            List<TelemetryLimitResponse> limits,
            List<TelemetryRunResponse> runs,
            boolean truncated) {
        return new StepTelemetryResponse(workOrderId, process, limits, runs, truncated);
    }
}

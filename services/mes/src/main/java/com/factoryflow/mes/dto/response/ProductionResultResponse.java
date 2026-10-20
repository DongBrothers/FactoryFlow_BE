package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.ProductionResult;
import com.factoryflow.mes.domain.StepResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "공정 실적 한 줄")
public record ProductionResultResponse(
        @Schema(description = "실적 결과", example = "GOOD") StepResult result,
        @Schema(description = "불량 사유 (양품이면 없음)", example = "도장 얼룩") String defectReason,
        @Schema(description = "작업자 (public_id)", example = "3f2a9c1e-0000-4000-8000-000000000001")
                String workerId,
        @Schema(description = "설비 코드", example = "PRESS-01") String equipmentCode,
        @Schema(description = "공정 시작 시각", example = "2026-10-15T09:00:00") LocalDateTime startedAt,
        @Schema(description = "공정 종료 시각", example = "2026-10-15T09:20:00") LocalDateTime endedAt) {

    public static ProductionResultResponse from(ProductionResult result) {
        return new ProductionResultResponse(
                result.getResult(),
                result.getDefectReason(),
                result.getWorkerId(),
                result.getEquipmentCode(),
                result.getStartedAt(),
                result.getEndedAt());
    }
}

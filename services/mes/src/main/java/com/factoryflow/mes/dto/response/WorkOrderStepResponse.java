package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.domain.StepStatus;
import com.factoryflow.mes.domain.WorkOrderStep;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "작업지시의 공정")
public record WorkOrderStepResponse(
        @Schema(description = "공정", example = "PRESS") ProductionProcess process,
        @Schema(description = "라우팅 순서 (1~5)", example = "1") int seq,
        @Schema(description = "공정 상태", example = "IN_PROGRESS") StepStatus status,
        @Schema(description = "진행 중인 설비 코드", example = "PRESS-01") String equipmentCode,
        @Schema(description = "시작 시각", example = "2026-10-15T09:00:00") LocalDateTime startedAt,
        @Schema(description = "종료 시각 (양품으로 끝났을 때)", example = "2026-10-15T09:20:00")
                LocalDateTime endedAt,
        @Schema(description = "이 공정의 실적 (재작업하면 여러 줄)") List<ProductionResultResponse> results) {

    public static WorkOrderStepResponse of(
            WorkOrderStep step, List<ProductionResultResponse> results) {
        return new WorkOrderStepResponse(
                step.getProcess(),
                step.getSeq(),
                step.getStatus(),
                step.getEquipmentCode(),
                step.getStartedAt(),
                step.getEndedAt(),
                results);
    }
}

package com.factoryflow.production.dto.request;

import com.factoryflow.production.domain.StepResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "공정 종료(실적) 요청. 불량이면 사유가 필요하다")
@DefectReasonRequired
public record CompleteWorkOrderStepRequest(
        @Schema(description = "실적 결과 (GOOD: 양품, DEFECT: 불량)", example = "GOOD")
                @NotNull(message = "실적 결과를 넣어야 합니다.")
                StepResult result,
        @Schema(description = "불량 사유 (DEFECT 일 때 필수)", example = "도장 얼룩")
                @Size(max = 255, message = "불량 사유는 255자 이하입니다.")
                String defectReason) {}

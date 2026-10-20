package com.factoryflow.mes.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "공정 시작 요청")
public record StartWorkOrderStepRequest(
        @Schema(description = "이 공정을 하는 설비 코드", example = "PRESS-01")
                @NotBlank(message = "설비를 지정해야 합니다.")
                String equipmentCode) {}

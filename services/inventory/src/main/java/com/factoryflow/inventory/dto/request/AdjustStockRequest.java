package com.factoryflow.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "재고 조정 요청")
public record AdjustStockRequest(
        @Schema(description = "늘릴 수량은 양수, 줄일 수량은 음수 (0 은 안 됨)", example = "-2")
                @NotNull(message = "qty 가 필요합니다.")
                @NotZero(message = "qty 는 0 이 아니어야 합니다.")
                Integer qty,
        @Schema(description = "사유 (255자 이하)", example = "운반 중 파손")
                @NotBlank(message = "사유가 필요합니다.")
                @Size(max = 255, message = "사유는 255자 이하여야 합니다.")
                String reason) {}

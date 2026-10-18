package com.factoryflow.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "재고 조정 요청")
public record AdjustStockRequest(
        @Schema(
                        description = "늘릴 수량은 양수, 줄일 수량은 음수 (0 은 안 됨, 한 번에 ±" + MAX_QTY + " 까지)",
                        example = "-2")
                @NotNull(message = "qty 가 필요합니다.")
                @NotZero(message = "qty 는 0 이 아니어야 합니다.")
                @Min(value = -MAX_QTY, message = "qty 는 {value} 이상이어야 합니다.")
                @Max(value = MAX_QTY, message = "qty 는 {value} 이하여야 합니다.")
                Integer qty,
        @Schema(description = "사유 (255자 이하)", example = "운반 중 파손")
                @NotBlank(message = "사유가 필요합니다.")
                @Size(max = 255, message = "사유는 255자 이하여야 합니다.")
                String reason) {

    /** 한 번에 조정할 수 있는 수량의 크기. 넘으면 수량 계산(부호 뒤집기, 더하기)이 넘칠 수 있다. */
    public static final int MAX_QTY = 1_000_000;
}

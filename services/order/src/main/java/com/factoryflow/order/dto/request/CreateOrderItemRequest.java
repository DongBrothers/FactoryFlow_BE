package com.factoryflow.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "주문 품목")
public record CreateOrderItemRequest(
        @Schema(description = "제품 번호", example = "1") @NotNull(message = "productId 가 필요합니다.")
                Long productId,
        @Schema(description = "주문 수량 (1 이상)", example = "2")
                @NotNull(message = "qty 가 필요합니다.")
                @Positive(message = "qty 는 1 이상이어야 합니다.")
                Integer qty) {}

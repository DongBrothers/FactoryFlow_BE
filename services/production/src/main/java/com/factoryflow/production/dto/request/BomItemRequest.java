package com.factoryflow.production.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "BOM 한 줄: 제품 1대에 드는 부품과 수량")
public record BomItemRequest(
        @Schema(description = "부품 id (inventory)", example = "1001")
                @NotNull(message = "partId 가 필요합니다.")
                Long partId,
        @Schema(description = "제품 1대에 드는 수량 (1 이상)", example = "4")
                @NotNull(message = "qty 가 필요합니다.")
                @Positive(message = "qty 는 1 이상이어야 합니다.")
                Integer qty) {}

package com.factoryflow.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

@Schema(description = "주문 생성 요청")
public record CreateOrderRequest(
        @Schema(description = "주문하는 고객 번호", example = "7")
                @NotNull(message = "고객 번호가 필요합니다.")
                @Positive(message = "고객 번호는 1 이상이어야 합니다.")
                Long customerId,
        @Schema(description = "주문할 제품 목록 (같은 제품은 한 번만)")
                @NotEmpty(message = "주문할 제품을 하나 이상 넣어야 합니다.")
                @UniqueProductIds
                List<@NotNull @Valid CreateOrderItemRequest> items) {}

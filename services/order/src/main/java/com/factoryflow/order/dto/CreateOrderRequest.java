package com.factoryflow.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record CreateOrderRequest(
        @NotEmpty(message = "주문할 제품을 하나 이상 넣어야 합니다.") @UniqueProductIds
                List<@NotNull @Valid Item> items) {

    public record Item(
            @NotNull(message = "productId 가 필요합니다.") Long productId,
            @NotNull(message = "qty 가 필요합니다.") @Positive(message = "qty 는 1 이상이어야 합니다.")
                    Integer qty) {}
}

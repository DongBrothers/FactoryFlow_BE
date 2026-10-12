package com.factoryflow.order.dto.response;

import com.factoryflow.order.domain.CustomerOrder;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 생성 응답")
public record CreateOrderResponse(
        @Schema(description = "주문 번호 (UUID)", example = "550e8400-e29b-41d4-a716-446655440000")
                String orderNo) {

    public static CreateOrderResponse from(CustomerOrder order) {
        return new CreateOrderResponse(order.getOrderNo());
    }
}

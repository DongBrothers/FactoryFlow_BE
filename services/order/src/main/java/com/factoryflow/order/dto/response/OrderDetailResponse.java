package com.factoryflow.order.dto.response;

import com.factoryflow.order.domain.CustomerOrder;
import com.factoryflow.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "주문 상세: 주문, 품목, 차량 상태")
public record OrderDetailResponse(
        @Schema(description = "주문 번호 (UUID)", example = "550e8400-e29b-41d4-a716-446655440000")
                String orderNo,
        @Schema(description = "고객 번호", example = "7") Long customerId,
        @Schema(description = "주문 상태 (품목들의 요약)", example = "IN_PRODUCTION") OrderStatus status,
        @Schema(description = "품목") List<OrderItemResponse> items) {

    public static OrderDetailResponse of(CustomerOrder order, List<OrderItemResponse> items) {
        return new OrderDetailResponse(
                order.getOrderNo(), order.getCustomerId(), order.getStatus(), items);
    }
}

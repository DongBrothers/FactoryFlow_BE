package com.factoryflow.order.dto.response;

import com.factoryflow.order.domain.CustomerOrder;
import com.factoryflow.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "주문 목록의 주문 하나 (품목·차량은 주문 상세에서)")
public record OrderSummaryResponse(
        @Schema(description = "주문 번호 (UUID)", example = "550e8400-e29b-41d4-a716-446655440000")
                String orderNo,
        @Schema(description = "고객 번호", example = "7") Long customerId,
        @Schema(description = "주문 상태", example = "IN_PRODUCTION") OrderStatus status,
        @Schema(description = "주문한 때", example = "2026-10-17T10:00:00") LocalDateTime createdAt) {

    public static OrderSummaryResponse from(CustomerOrder order) {
        return new OrderSummaryResponse(
                order.getOrderNo(), order.getCustomerId(), order.getStatus(), order.getCreatedAt());
    }
}

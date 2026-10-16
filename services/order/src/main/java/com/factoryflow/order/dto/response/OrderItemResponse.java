package com.factoryflow.order.dto.response;

import com.factoryflow.order.domain.OrderItem;
import com.factoryflow.order.domain.OrderStatus;
import com.factoryflow.order.domain.OrderVehicle;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "주문 품목")
public record OrderItemResponse(
        @Schema(description = "제품 번호", example = "1") Long productId,
        @Schema(description = "제품 코드 (주문 당시)", example = "AVANTE") String productCode,
        @Schema(description = "제품 이름 (주문 당시)", example = "아반떼") String productName,
        @Schema(description = "수량", example = "2") int qty,
        @Schema(description = "품목 상태 (차량들의 요약)", example = "IN_PRODUCTION") OrderStatus status,
        @Schema(description = "알려진 차량 (생산 건이 생긴 것만)") List<OrderVehicleResponse> vehicles) {

    public static OrderItemResponse of(OrderItem item, List<OrderVehicle> vehicles) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getProductCode(),
                item.getProductName(),
                item.getQty(),
                item.getStatus(),
                vehicles.stream().map(OrderVehicleResponse::from).toList());
    }
}

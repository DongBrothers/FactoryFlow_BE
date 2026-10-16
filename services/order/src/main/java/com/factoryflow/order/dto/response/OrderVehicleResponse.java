package com.factoryflow.order.dto.response;

import com.factoryflow.order.domain.OrderStatus;
import com.factoryflow.order.domain.OrderVehicle;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 품목 속 차량 1대")
public record OrderVehicleResponse(
        @Schema(description = "차량 상태", example = "IN_PRODUCTION") OrderStatus status,
        @Schema(description = "차대번호 (생산 완료 후)", example = "KMH000017TU000012") String vin) {

    public static OrderVehicleResponse from(OrderVehicle vehicle) {
        return new OrderVehicleResponse(vehicle.getStatus(), vehicle.getVin());
    }
}

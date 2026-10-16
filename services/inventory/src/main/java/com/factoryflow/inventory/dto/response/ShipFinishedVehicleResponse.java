package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.FinishedVehicle;
import com.factoryflow.inventory.domain.FinishedVehicleStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "출고한 완성차")
public record ShipFinishedVehicleResponse(
        @Schema(description = "차대번호", example = "KMH000017TU000012") String vin,
        @Schema(description = "제품 id", example = "1") Long productId,
        @Schema(description = "완성차 상태", example = "SHIPPED") FinishedVehicleStatus status,
        @Schema(description = "출고한 때", example = "2026-10-16T21:00:00") LocalDateTime shippedAt) {

    public static ShipFinishedVehicleResponse from(FinishedVehicle vehicle) {
        return new ShipFinishedVehicleResponse(
                vehicle.getVin(),
                vehicle.getProductId(),
                vehicle.getStatus(),
                vehicle.getShippedAt());
    }
}

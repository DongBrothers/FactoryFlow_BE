package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.FinishedVehicle;
import com.factoryflow.inventory.domain.FinishedVehicleStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "완성차 목록의 차 한 대")
public record FinishedVehicleSummaryResponse(
        @Schema(description = "차대번호", example = "KMH000017TU000012") String vin,
        @Schema(description = "제품 id", example = "1") Long productId,
        @Schema(description = "완성차 상태 (IN_STOCK 창고에 있음, SHIPPED 출고됨)", example = "IN_STOCK")
                FinishedVehicleStatus status,
        @Schema(description = "출고장 자리. 자리 대기면 없음, 출고한 차는 나간 자리", example = "A-01") String slotCode,
        @Schema(description = "완성차 재고에 들어온 때", example = "2026-10-17T10:00:00")
                LocalDateTime completedAt,
        @Schema(description = "출고한 때. 출고 전이면 없음", example = "2026-10-17T15:00:00")
                LocalDateTime shippedAt) {

    public static FinishedVehicleSummaryResponse from(FinishedVehicle vehicle) {
        return new FinishedVehicleSummaryResponse(
                vehicle.getVin(),
                vehicle.getProductId(),
                vehicle.getStatus(),
                vehicle.getSlotCode(),
                vehicle.getCreatedAt(),
                vehicle.getShippedAt());
    }
}

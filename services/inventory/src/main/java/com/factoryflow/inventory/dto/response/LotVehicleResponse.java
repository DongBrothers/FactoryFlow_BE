package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.FinishedVehicle;
import com.factoryflow.inventory.domain.FinishedVehicleStatus;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartLotUsage;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "LOT 의 부품이 들어간 차 한 대")
public record LotVehicleResponse(
        @Schema(description = "차대번호", example = "KMH000017TU000012") String vin,
        @Schema(description = "제품 id", example = "1") Long productId,
        @Schema(description = "완성차 상태 (IN_STOCK 창고에 있음, SHIPPED 출고됨)", example = "SHIPPED")
                FinishedVehicleStatus status,
        @Schema(description = "부품 코드", example = "ENG-G16") String partCode,
        @Schema(description = "그 LOT 에서 쓴 수량", example = "1") int qty) {

    public static LotVehicleResponse of(FinishedVehicle vehicle, Part part, PartLotUsage usage) {
        return new LotVehicleResponse(
                vehicle.getVin(),
                vehicle.getProductId(),
                vehicle.getStatus(),
                part.getCode(),
                usage.getQty());
    }
}

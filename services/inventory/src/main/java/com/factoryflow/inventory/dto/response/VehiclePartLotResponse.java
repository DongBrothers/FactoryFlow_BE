package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartLot;
import com.factoryflow.inventory.domain.PartLotUsage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "차에 쓰인 부품 LOT 하나")
public record VehiclePartLotResponse(
        @Schema(description = "부품 코드", example = "ENG-G16") String partCode,
        @Schema(description = "부품 이름", example = "가솔린 엔진 1.6") String partName,
        @Schema(description = "LOT 번호. 없으면 LOT 도입 전 재고", example = "LOT-261017-000012")
                String lotNo,
        @Schema(description = "그 LOT 에서 쓴 수량", example = "1") int qty,
        @Schema(description = "LOT 입고 시각. LOT 이 없으면 없음", example = "2026-10-17T10:00:00")
                LocalDateTime receivedAt) {

    /** lot 이 null 이면 LOT 도입 전 재고에서 썼다. */
    public static VehiclePartLotResponse of(PartLotUsage usage, Part part, PartLot lot) {
        return new VehiclePartLotResponse(
                part.getCode(),
                part.getName(),
                lot == null ? null : lot.getLotNo(),
                usage.getQty(),
                lot == null ? null : lot.getReceivedAt());
    }
}

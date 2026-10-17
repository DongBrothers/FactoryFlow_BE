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
        @Schema(description = "LOT 번호. 초기 재고는 LOT-INIT-{부품 id}", example = "LOT-261017-000012")
                String lotNo,
        @Schema(description = "그 LOT 에서 쓴 수량", example = "1") int qty,
        @Schema(description = "LOT 입고 시각", example = "2026-10-17T10:00:00")
                LocalDateTime receivedAt) {

    public static VehiclePartLotResponse of(PartLotUsage usage, Part part, PartLot lot) {
        return new VehiclePartLotResponse(
                part.getCode(),
                part.getName(),
                lot.getLotNo(),
                usage.getQty(),
                lot.getReceivedAt());
    }
}

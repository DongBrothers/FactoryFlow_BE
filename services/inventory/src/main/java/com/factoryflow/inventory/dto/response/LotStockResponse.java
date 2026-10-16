package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.PartLot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "남은 부품 LOT 하나")
public record LotStockResponse(
        @Schema(description = "LOT 번호", example = "LOT-261017-000012") String lotNo,
        @Schema(description = "입고 수량", example = "10") int receivedQty,
        @Schema(description = "아직 쓰지 않은 수량", example = "7") int remainingQty,
        @Schema(description = "입고 시각", example = "2026-10-17T10:00:00") LocalDateTime receivedAt) {

    public static LotStockResponse from(PartLot lot) {
        return new LotStockResponse(
                lot.getLotNo(), lot.getReceivedQty(), lot.getRemainingQty(), lot.getReceivedAt());
    }
}

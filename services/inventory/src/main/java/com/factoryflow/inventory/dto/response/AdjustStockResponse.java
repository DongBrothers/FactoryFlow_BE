package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.StockAdjustment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "재고 조정 결과")
public record AdjustStockResponse(
        @Schema(description = "조정 id", example = "12") Long adjustmentId,
        @Schema(description = "부품 id", example = "1012") Long partId,
        @Schema(description = "조정한 수량 (늘렸으면 양수, 줄였으면 음수)", example = "-2") int qty,
        @Schema(description = "사유", example = "운반 중 파손") String reason,
        @Schema(description = "조정 뒤 바로 쓸 수 있는 수량", example = "4") int available,
        @Schema(description = "조정 뒤 생산에 잡힌 수량", example = "6") int reserved,
        @Schema(description = "늘렸을 때 만든 조정 LOT 번호 (줄였으면 없음)", example = "LOT-ADJ-000012")
                String lotNo,
        @Schema(description = "줄일 수량이 바로 쓸 수 있는 수량보다 많아 부품을 거둔 생산 건 id (부품 대기로 돌아감)")
                List<Long> reclaimedProductionIds) {

    public static AdjustStockResponse of(
            StockAdjustment adjustment,
            Part part,
            String lotNo,
            List<Long> reclaimedProductionIds) {
        return new AdjustStockResponse(
                adjustment.getId(),
                part.getId(),
                adjustment.getQty(),
                adjustment.getReason(),
                part.getAvailable(),
                part.getReserved(),
                lotNo,
                reclaimedProductionIds);
    }
}

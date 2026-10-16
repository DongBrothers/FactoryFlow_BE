package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.Part;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "부품의 LOT 별 재고. LOT 없는 재고는 LOT 을 붙이기 전부터 있던 재고로, 생산에 먼저 쓴다")
public record PartLotStockResponse(
        @Schema(description = "부품 코드", example = "ENG-G16") String partCode,
        @Schema(description = "부품 이름", example = "가솔린 엔진 1.6") String partName,
        @Schema(description = "창고 실물 (바로 쓸 수 있는 수량 + 생산에 잡힌 수량)", example = "300") int onHand,
        @Schema(description = "LOT 없는 재고 (실물 − LOT 남은 수량 합)", example = "290") int withoutLotQty,
        @Schema(description = "남은 LOT, 다음에 쓸 순서(입고 순)") List<LotStockResponse> lots) {

    public static PartLotStockResponse of(Part part, List<LotStockResponse> lots) {
        int lotted = lots.stream().mapToInt(LotStockResponse::remainingQty).sum();
        return new PartLotStockResponse(
                part.getCode(),
                part.getName(),
                part.onHand(),
                Math.max(0, part.onHand() - lotted),
                lots);
    }
}

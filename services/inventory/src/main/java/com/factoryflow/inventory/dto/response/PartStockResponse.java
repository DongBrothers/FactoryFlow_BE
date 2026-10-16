package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.Part;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "부품 재고. 창고 실물 = available + reserved")
public record PartStockResponse(
        @Schema(description = "부품 id", example = "1001") Long partId,
        @Schema(description = "부품 코드", example = "ENG-G16") String code,
        @Schema(description = "부품 이름", example = "가솔린 엔진 1.6") String name,
        @Schema(description = "단위 (EA, SET, KG)", example = "EA") String unit,
        @Schema(description = "바로 쓸 수 있는 수량", example = "299") int available,
        @Schema(description = "생산에 잡힌 수량", example = "1") int reserved) {

    public static PartStockResponse from(Part part) {
        return new PartStockResponse(
                part.getId(),
                part.getCode(),
                part.getName(),
                part.getUnit(),
                part.getAvailable(),
                part.getReserved());
    }
}

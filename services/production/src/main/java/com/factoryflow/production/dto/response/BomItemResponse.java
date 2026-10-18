package com.factoryflow.production.dto.response;

import com.factoryflow.production.domain.Bom;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "BOM 한 줄")
public record BomItemResponse(
        @Schema(description = "부품 id (inventory)", example = "1001") Long partId,
        @Schema(description = "제품 1대에 드는 수량", example = "4") int qty) {

    public static BomItemResponse from(Bom bom) {
        return new BomItemResponse(bom.getPartId(), bom.getQtyPerUnit());
    }
}

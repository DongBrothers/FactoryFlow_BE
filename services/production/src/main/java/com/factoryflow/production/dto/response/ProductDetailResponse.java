package com.factoryflow.production.dto.response;

import com.factoryflow.production.domain.Bom;
import com.factoryflow.production.domain.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Comparator;
import java.util.List;

@Schema(description = "제품 상세 (BOM 포함)")
public record ProductDetailResponse(
        @Schema(description = "제품 id. 주문 생성의 productId 로 쓴다", example = "31") Long productId,
        @Schema(description = "제품 코드", example = "IONIQ5") String code,
        @Schema(description = "제품 이름", example = "아이오닉 5") String name,
        @Schema(description = "BOM (삭제되지 않은 줄, 부품 id 순)") List<BomItemResponse> bom) {

    public static ProductDetailResponse of(Product product, List<Bom> bom) {
        return new ProductDetailResponse(
                product.getId(),
                product.getCode(),
                product.getName(),
                bom.stream()
                        .filter(line -> !line.isDeleted())
                        .sorted(Comparator.comparing(Bom::getPartId))
                        .map(BomItemResponse::from)
                        .toList());
    }
}

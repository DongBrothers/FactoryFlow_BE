package com.factoryflow.production.dto.response;

import com.factoryflow.production.domain.Product;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문할 수 있는 제품")
public record ProductResponse(
        @Schema(description = "제품 id (주문 생성 요청에 넣는다)", example = "1") Long productId,
        @Schema(description = "제품 코드", example = "AVANTE") String code,
        @Schema(description = "제품 이름", example = "아반떼") String name) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getCode(), product.getName());
    }
}

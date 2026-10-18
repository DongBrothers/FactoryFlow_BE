package com.factoryflow.production.dto.response;

import com.factoryflow.production.domain.Product;
import com.factoryflow.production.domain.Production;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "작업지시 발행을 기다리는 생산 건 (부품이 다 잡힌 차 1대)")
public record AwaitingReleaseResponse(
        @Schema(description = "생산 건 번호. 작업지시 발행에 쓴다", example = "101") Long productionId,
        @Schema(description = "주문 번호 (order 서비스의 주문 id)", example = "10") Long orderId,
        @Schema(description = "제품 번호", example = "1") Long productId,
        @Schema(description = "제품 코드", example = "AVANTE") String productCode,
        @Schema(description = "제품 이름", example = "아반떼") String productName,
        @Schema(description = "그 주문·제품 안의 차량 번호", example = "1") int seq) {

    public static AwaitingReleaseResponse of(Production production, Product product) {
        return new AwaitingReleaseResponse(
                production.getId(),
                production.getOrderId(),
                production.getProductId(),
                product == null ? null : product.getCode(),
                product == null ? null : product.getName(),
                production.getSeq());
    }
}

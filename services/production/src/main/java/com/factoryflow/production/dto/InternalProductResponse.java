package com.factoryflow.production.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "서비스 간 제품 조회 응답")
public record InternalProductResponse(
        @Schema(description = "제품 번호", example = "1") Long id,
        @Schema(description = "제품 코드", example = "CAR-A") String code,
        @Schema(description = "제품 이름", example = "소형차 A") String name,
        @Schema(description = "BOM 이 있어 주문할 수 있는지", example = "true") boolean orderable) {}

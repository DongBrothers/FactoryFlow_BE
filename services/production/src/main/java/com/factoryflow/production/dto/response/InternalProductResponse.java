package com.factoryflow.production.dto.response;

import com.factoryflow.production.domain.Product;

/** 서비스 간 제품 조회 응답. orderable 은 삭제되지 않은 BOM 이 있어 주문할 수 있는지다. */
public record InternalProductResponse(Long id, String code, String name, boolean orderable) {

    public static InternalProductResponse of(Product product, boolean orderable) {
        return new InternalProductResponse(
                product.getId(), product.getCode(), product.getName(), orderable);
    }
}

package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.Part;

/** 내부 API 응답: 부품 확인 (purchase 가 구매 건을 만들 때 부른다). */
public record InternalPartResponse(Long id, String code, String name) {

    public static InternalPartResponse from(Part part) {
        return new InternalPartResponse(part.getId(), part.getCode(), part.getName());
    }
}

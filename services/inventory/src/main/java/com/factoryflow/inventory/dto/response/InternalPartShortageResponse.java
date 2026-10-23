package com.factoryflow.inventory.dto.response;

/** 부족 부품 (내부 API). 값은 공개 부족 부품 조회와 같다 (docs/specs/purchase-suggestion.md). */
public record InternalPartShortageResponse(
        Long partId,
        String code,
        String name,
        String unit,
        int available,
        int waitingQty,
        int shortQty,
        int waitingProductions) {

    public static InternalPartShortageResponse from(PartShortageResponse shortage) {
        return new InternalPartShortageResponse(
                shortage.partId(),
                shortage.code(),
                shortage.name(),
                shortage.unit(),
                shortage.available(),
                shortage.waitingQty(),
                shortage.shortQty(),
                shortage.waitingProductions());
    }
}

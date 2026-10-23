package com.factoryflow.production.dto.response;

import com.factoryflow.production.domain.Production;
import java.util.List;

/** 부품 대기로 되돌린 생산 건 id (요청의 후보 순서). */
public record InternalMaterialHoldResponse(List<Long> heldProductionIds) {

    public static InternalMaterialHoldResponse from(List<Production> held) {
        return new InternalMaterialHoldResponse(held.stream().map(Production::getId).toList());
    }
}

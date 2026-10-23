package com.factoryflow.production.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 거둘 후보: 그 부품을 qty 개 잡아 둔 생산 건. */
public record InternalHoldMaterialCandidateRequest(
        @NotNull(message = "productionId 가 필요합니다.")
                @Positive(message = "productionId 는 1 이상이어야 합니다.")
                Long productionId,
        @NotNull(message = "qty 가 필요합니다.") @Positive(message = "qty 는 1 이상이어야 합니다.") Integer qty) {}

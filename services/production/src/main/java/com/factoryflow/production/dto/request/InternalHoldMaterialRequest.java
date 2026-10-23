package com.factoryflow.production.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 재고 조정으로 부품 neededQty 개를 거둬야 할 때 inventory 가 보내는 요청. candidates 는 그 부품을 잡아 둔 생산 건으로, 늦게 잡은 예약부터 온다
 * (docs/specs/stock-adjustment.md "찜한 부품 거둬들이기").
 */
public record InternalHoldMaterialRequest(
        @NotNull(message = "partId 가 필요합니다.") @Positive(message = "partId 는 1 이상이어야 합니다.")
                Long partId,
        @NotNull(message = "neededQty 가 필요합니다.") @Positive(message = "neededQty 는 1 이상이어야 합니다.")
                Integer neededQty,
        @NotEmpty(message = "후보 생산 건이 하나 이상 필요합니다.")
                List<@NotNull @Valid InternalHoldMaterialCandidateRequest> candidates) {}

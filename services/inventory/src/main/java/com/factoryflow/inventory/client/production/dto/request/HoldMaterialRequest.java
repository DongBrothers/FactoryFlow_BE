package com.factoryflow.inventory.client.production.dto.request;

import java.util.List;

/** production 에 보내는 거둘 요청. candidates 는 늦게 잡은 예약부터. */
public record HoldMaterialRequest(
        Long partId, int neededQty, List<HoldMaterialCandidateRequest> candidates) {}

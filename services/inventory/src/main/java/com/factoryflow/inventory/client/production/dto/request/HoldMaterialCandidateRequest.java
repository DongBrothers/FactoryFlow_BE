package com.factoryflow.inventory.client.production.dto.request;

/** 거둘 후보: 그 부품을 qty 개 잡아 둔 생산 건. */
public record HoldMaterialCandidateRequest(Long productionId, int qty) {}

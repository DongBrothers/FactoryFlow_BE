package com.factoryflow.inventory.client.production.dto.response;

import java.util.List;

/** production 이 부품 대기로 되돌린 생산 건 id. */
public record MaterialHoldResponse(List<Long> heldProductionIds) {}

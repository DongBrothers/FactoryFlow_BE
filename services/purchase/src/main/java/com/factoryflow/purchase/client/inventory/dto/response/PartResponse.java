package com.factoryflow.purchase.client.inventory.dto.response;

/** inventory 가 알려 준 부품 정보. 구매 건에 코드·이름을 스냅샷으로 남긴다. */
public record PartResponse(Long id, String code, String name) {}

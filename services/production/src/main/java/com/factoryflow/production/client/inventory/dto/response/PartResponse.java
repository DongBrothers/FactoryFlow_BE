package com.factoryflow.production.client.inventory.dto.response;

/** inventory 가 알려 준 부품 정보. BOM 의 부품이 있는지 확인한다 (docs/specs/master-data.md). */
public record PartResponse(Long id, String code, String name) {}

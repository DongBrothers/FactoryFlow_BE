package com.factoryflow.order.client.production.dto.response;

/** production 이 알려 준 제품 정보. orderable 은 BOM 이 있어 주문할 수 있는지. */
public record ProductInfo(Long id, String code, String name, boolean orderable) {}

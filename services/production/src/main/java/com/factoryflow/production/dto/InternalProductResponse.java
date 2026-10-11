package com.factoryflow.production.dto;

/** 서비스 간 제품 조회 응답. orderable 은 BOM 이 있어 주문할 수 있는지. */
public record InternalProductResponse(Long id, String code, String name, boolean orderable) {}

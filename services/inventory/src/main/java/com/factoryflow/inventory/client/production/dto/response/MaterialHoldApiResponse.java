package com.factoryflow.inventory.client.production.dto.response;

/** production 내부 API 의 공통 응답(ApiResponse) 중 data 만 읽는다. */
public record MaterialHoldApiResponse(MaterialHoldResponse data) {}

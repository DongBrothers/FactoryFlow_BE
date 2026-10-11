package com.factoryflow.order.client.production.dto.response;

import java.util.List;

/** production 내부 API 의 공통 응답(ApiResponse) 중 data 만 읽는다. */
public record ProductsResponse(List<ProductResponse> data) {}

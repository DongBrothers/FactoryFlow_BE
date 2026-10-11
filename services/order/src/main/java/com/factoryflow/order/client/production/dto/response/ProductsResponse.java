package com.factoryflow.order.client.production.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** production 내부 API 의 공통 응답(ApiResponse) 중 data 만 읽는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductsResponse(List<ProductInfo> data) {}

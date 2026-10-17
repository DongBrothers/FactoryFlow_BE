package com.factoryflow.purchase.client.inventory.dto.response;

import java.util.List;

/** inventory 내부 API 의 공통 응답(ApiResponse) 중 data 만 읽는다. */
public record PartsResponse(List<PartResponse> data) {}

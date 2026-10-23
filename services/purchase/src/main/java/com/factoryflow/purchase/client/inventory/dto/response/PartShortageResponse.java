package com.factoryflow.purchase.client.inventory.dto.response;

/** inventory 의 부족 부품 한 줄 (GET /internal/parts/shortages). */
public record PartShortageResponse(
        Long partId,
        String code,
        String name,
        String unit,
        int available,
        int waitingQty,
        int shortQty) {}

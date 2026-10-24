package com.factoryflow.production.event.listener.payload;

/** 받은 mes.vehicle-scrapped 의 payload (docs/specs/defect-scrap.md). 공정 불량으로 차를 폐기했다. */
public record MesVehicleScrapped(
        Long productionId, Long workOrderId, String process, String defectReason) {}

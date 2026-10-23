package com.factoryflow.inventory.event.listener.payload;

/** 받은 production.scrapped 의 payload (docs/specs/defect-scrap.md). 공정 불량으로 차를 폐기했다. */
public record ProductionScrapped(Long orderId, Long productionId) {}

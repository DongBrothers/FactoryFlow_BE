package com.factoryflow.inventory.event.listener.payload;

import java.util.List;

/** 받은 production.completed 의 payload (docs/specs/events.md). */
public record ProductionCompleted(
        Long orderId,
        Long productionId,
        Long productId,
        String vin,
        List<ScannedPartLot> scannedLots) {

    /** 이 변경 전 메시지에는 scannedLots 가 없다 (docs/specs/part-lot-scan.md). */
    public List<ScannedPartLot> scannedLotsOrEmpty() {
        return scannedLots == null ? List.of() : scannedLots;
    }
}

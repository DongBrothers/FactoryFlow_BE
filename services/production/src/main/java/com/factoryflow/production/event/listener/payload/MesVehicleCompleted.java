package com.factoryflow.production.event.listener.payload;

import java.util.List;

/** 받은 mes.vehicle-completed 의 payload (docs/specs/mes-split.md). 검사 양품으로 차가 완성돼 VIN 을 발행했다. */
public record MesVehicleCompleted(
        Long productionId, Long workOrderId, String vin, List<MesScannedLot> scannedLots) {

    /** 이 변경 전 메시지에는 scannedLots 가 없다 (docs/specs/part-lot-scan.md). */
    public List<MesScannedLot> scannedLotsOrEmpty() {
        return scannedLots == null ? List.of() : scannedLots;
    }
}

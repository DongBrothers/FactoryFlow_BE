package com.factoryflow.mes.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import java.util.List;

/** 검사 양품으로 차가 완성돼 VIN 을 발행했다. production 이 생산 건을 COMPLETED 로 바꾸고 production.completed 를 보낸다. */
/** 검사 양품으로 차를 완성했다. scannedLots 는 의장 공정에서 찍은 부품 LOT (없으면 빈 목록, docs/specs/part-lot-scan.md). */
public record VehicleCompleted(
        Long productionId, Long workOrderId, String vin, List<ScannedLot> scannedLots)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "mes.vehicle-completed";
    }
}

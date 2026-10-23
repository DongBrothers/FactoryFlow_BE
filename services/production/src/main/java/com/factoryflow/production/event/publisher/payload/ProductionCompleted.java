package com.factoryflow.production.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import java.util.List;

/** 검사 양품으로 차 한 대가 완성됐다. inventory 는 부품을 사용 처리하고 완성차 재고를 늘리고, order 는 차량을 COMPLETED 로 바꾼다. */
/** 생산 완료. scannedLots 는 의장 공정에서 찍은 부품 LOT (없으면 빈 목록, docs/specs/part-lot-scan.md). */
public record ProductionCompleted(
        Long orderId,
        Long productionId,
        Long productId,
        String vin,
        List<CompletedPartLot> scannedLots)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "production.completed";
    }
}

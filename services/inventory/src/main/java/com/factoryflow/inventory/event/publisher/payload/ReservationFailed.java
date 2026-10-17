package com.factoryflow.inventory.event.publisher.payload;

import com.factoryflow.common.event.DomainEvent;
import java.util.List;

/** 생산 건에 필요한 부품을 예약할 수 없다. partIds 는 없거나 삭제된 부품 (docs/specs/stalled-flows.md S18). */
public record ReservationFailed(Long productionId, String reason, List<Long> partIds)
        implements DomainEvent {

    public static final String PART_NOT_FOUND = "PART_NOT_FOUND";

    @Override
    public String eventName() {
        return "inventory.reservation-failed";
    }
}

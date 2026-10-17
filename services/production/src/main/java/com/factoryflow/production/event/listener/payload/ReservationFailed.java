package com.factoryflow.production.event.listener.payload;

import java.util.List;

/** 받은 inventory.reservation-failed 의 payload (docs/specs/events.md). */
public record ReservationFailed(Long productionId, String reason, List<Long> partIds) {}

package com.factoryflow.simulator.event.publisher.payload;

import java.time.Instant;
import java.util.UUID;

/** 보내는 이벤트 메시지. common-event 의 EventEnvelope 와 같은 JSON 이다. */
public record OutgoingEvent(
        UUID eventId,
        String eventName,
        String traceId,
        int version,
        Instant occurredAt,
        Object payload) {}

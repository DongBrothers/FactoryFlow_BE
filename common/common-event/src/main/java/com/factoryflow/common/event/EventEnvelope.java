package com.factoryflow.common.event;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** RabbitMQ로 오가는 이벤트 메시지. 공통 필드 + payload. */
public record EventEnvelope(
        UUID eventId,
        String eventName,
        String traceId,
        int version,
        Instant occurredAt,
        JsonNode payload) {}

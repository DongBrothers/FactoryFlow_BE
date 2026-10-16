package com.factoryflow.simulator.event.listener.payload;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 받은 이벤트 메시지. common-event 의 EventEnvelope 와 같은 JSON 이다 (simulator 는 common-event 를 쓰지 않음). */
public record ReceivedEvent(
        UUID eventId,
        String eventName,
        String traceId,
        int version,
        Instant occurredAt,
        JsonNode payload) {}

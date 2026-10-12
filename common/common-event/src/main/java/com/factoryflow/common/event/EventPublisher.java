package com.factoryflow.common.event;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 이벤트를 호출한 쪽의 트랜잭션 안에서 outbox 테이블에 저장한다. 실제 발행은 {@link OutboxRelay}가 한다. */
public class EventPublisher {

    static final String TRACE_ID_MDC_KEY = "traceId";

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EventPublisher(EntityManager entityManager, ObjectMapper objectMapper, Clock clock) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope publish(DomainEvent event) {
        Instant now = clock.instant();
        EventEnvelope envelope =
                new EventEnvelope(
                        UUID.randomUUID(),
                        event.eventName(),
                        currentTraceId(),
                        event.version(),
                        now,
                        objectMapper.valueToTree(event));

        entityManager
                .createNativeQuery(
                        "INSERT INTO outbox (event_id, event_name, payload, created_at)"
                                + " VALUES (?1, ?2, ?3, ?4)")
                .setParameter(1, envelope.eventId().toString())
                .setParameter(2, envelope.eventName())
                .setParameter(3, objectMapper.writeValueAsString(envelope))
                .setParameter(4, LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
                .executeUpdate();
        return envelope;
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}

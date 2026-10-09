package com.factoryflow.common.event;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 이벤트를 호출한 쪽의 트랜잭션 안에서 outbox 테이블에 저장한다. 실제 발행은 {@link OutboxRelay}가 한다.
 */
public class EventPublisher {

    static final String TRACE_ID_MDC_KEY = "traceId";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EventPublisher(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope publish(DomainEvent event) {
        Instant now = clock.instant();
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                event.eventName(),
                currentTraceId(),
                event.version(),
                now,
                objectMapper.valueToTree(event));

        jdbcTemplate.update(
                "INSERT INTO outbox (event_id, event_name, payload, created_at) VALUES (?, ?, ?, ?)",
                envelope.eventId().toString(),
                envelope.eventName(),
                objectMapper.writeValueAsString(envelope),
                Timestamp.from(now));
        return envelope;
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}

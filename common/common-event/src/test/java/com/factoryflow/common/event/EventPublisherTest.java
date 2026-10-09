package com.factoryflow.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class EventPublisherTest extends EventIntegrationTest {

    @Autowired
    EventPublisher eventPublisher;

    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 트랜잭션_안에서_발행하면_공통필드와_payload가_outbox에_저장된다() {
        MDC.put("traceId", "trace-1");

        EventEnvelope envelope = publishInTransaction(eventPublisher, new TestEvent(42L));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT event_id, event_name, payload, published_at FROM outbox");
        assertThat(row.get("event_id")).isEqualTo(envelope.eventId().toString());
        assertThat(row.get("event_name")).isEqualTo(TestEvent.NAME);
        assertThat(row.get("published_at")).isNull();

        JsonNode stored = objectMapper.readTree((String) row.get("payload"));
        assertThat(stored.get("eventId").asString()).isEqualTo(envelope.eventId().toString());
        assertThat(stored.get("eventName").asString()).isEqualTo(TestEvent.NAME);
        assertThat(stored.get("traceId").asString()).isEqualTo("trace-1");
        assertThat(stored.get("version").asInt()).isEqualTo(1);
        assertThat(stored.get("occurredAt").isNull()).isFalse();
        assertThat(stored.get("payload").get("orderId").asLong()).isEqualTo(42L);
    }

    @Test
    void 트랜잭션_밖에서_발행하면_실패하고_저장되지_않는다() {
        assertThatThrownBy(() -> eventPublisher.publish(new TestEvent(1L)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void 트랜잭션이_롤백되면_outbox도_롤백된다() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publish(new TestEvent(1L));
            throw new IllegalStateException("업무 처리 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }
}

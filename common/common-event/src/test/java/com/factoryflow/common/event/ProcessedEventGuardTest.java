package com.factoryflow.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

class ProcessedEventGuardTest extends EventIntegrationTest {

    @Autowired ProcessedEventGuard guard;

    @Test
    void 메시지를_처리하는_동안_받은_traceId_를_MDC_에_두고_끝나면_지운다() {
        EventEnvelope envelope = envelope("trace-abc");
        AtomicReference<String> seen = new AtomicReference<>();

        guard.runOnce(envelope, () -> seen.set(MDC.get(EventPublisher.TRACE_ID_MDC_KEY)));

        assertThat(seen).hasValue("trace-abc");
        assertThat(MDC.get(EventPublisher.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void 이미_MDC_에_traceId_가_있었으면_끝난_뒤_그_값으로_돌린다() {
        MDC.put(EventPublisher.TRACE_ID_MDC_KEY, "outer");
        try {
            guard.runOnce(envelope("trace-abc"), () -> {});

            assertThat(MDC.get(EventPublisher.TRACE_ID_MDC_KEY)).isEqualTo("outer");
        } finally {
            MDC.remove(EventPublisher.TRACE_ID_MDC_KEY);
        }
    }

    private static EventEnvelope envelope(String traceId) {
        return new EventEnvelope(UUID.randomUUID(), "test.event", traceId, 1, Instant.now(), null);
    }

    @Test
    void 같은_이벤트를_두번_받으면_한번만_처리한다() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger();

        boolean first = guard.runOnce(eventId, handled::incrementAndGet);
        boolean second = guard.runOnce(eventId, handled::incrementAndGet);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(handled).hasValue(1);
    }

    @Test
    void 바깥_트랜잭션_안에서_중복을_받아도_건너뛰고_바깥_트랜잭션은_커밋된다() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger();
        guard.runOnce(eventId, handled::incrementAndGet);

        boolean second = inTransaction.call(() -> guard.runOnce(eventId, handled::incrementAndGet));

        assertThat(second).isFalse();
        assertThat(handled).hasValue(1);
    }

    @Test
    void 중복이_아닌_오류는_이미_처리한_것으로_넘기지_않고_예외를_낸다() {
        // event_id 를 줄여 UUID(36자)가 잘리게 한다. INSERT IGNORE 는 이를 오류 대신 경고로 바꾼다
        jdbcTemplate.execute("ALTER TABLE processed_event MODIFY event_id CHAR(10) NOT NULL");
        try {
            AtomicInteger handled = new AtomicInteger();

            assertThatThrownBy(() -> guard.runOnce(UUID.randomUUID(), handled::incrementAndGet))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(handled).hasValue(0);
        } finally {
            jdbcTemplate.update("DELETE FROM processed_event");
            jdbcTemplate.execute("ALTER TABLE processed_event MODIFY event_id CHAR(36) NOT NULL");
        }
    }

    @Test
    void 처리_중_실패하면_처리기록도_롤백되어_재수신시_다시_처리한다() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger();

        assertThatThrownBy(
                        () ->
                                guard.runOnce(
                                        eventId,
                                        () -> {
                                            throw new IllegalStateException("처리 실패");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        boolean retried = guard.runOnce(eventId, handled::incrementAndGet);

        assertThat(retried).isTrue();
        assertThat(handled).hasValue(1);
    }
}

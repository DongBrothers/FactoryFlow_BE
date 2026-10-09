package com.factoryflow.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProcessedEventGuardTest extends EventIntegrationTest {

    @Autowired
    ProcessedEventGuard guard;

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
    void 처리_중_실패하면_처리기록도_롤백되어_재수신시_다시_처리한다() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger();

        assertThatThrownBy(() -> guard.runOnce(eventId, () -> {
            throw new IllegalStateException("처리 실패");
        })).isInstanceOf(IllegalStateException.class);
        boolean retried = guard.runOnce(eventId, handled::incrementAndGet);

        assertThat(retried).isTrue();
        assertThat(handled).hasValue(1);
    }
}

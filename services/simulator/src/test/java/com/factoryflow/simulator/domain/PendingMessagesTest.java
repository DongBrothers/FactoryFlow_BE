package com.factoryflow.simulator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 브로커 확인을 못 받은 메시지만, 다시 보낼 때가 된 것만 다시 보낸다. */
class PendingMessagesTest {

    static final Duration INTERVAL = Duration.ofSeconds(1);
    static final Instant T0 = Instant.parse("2026-10-16T09:00:00Z");

    @Test
    void ackedMessageIsNotResent() {
        PendingMessages<String> pending = new PendingMessages<>();
        pending.sent("a", "A", T0);
        pending.sent("b", "B", T0);

        pending.acked("a");

        assertThat(pending.dueForResend(T0.plusSeconds(2), INTERVAL)).containsExactly("B");
        assertThat(pending.size()).isEqualTo(1);
    }

    @Test
    void messageIsResentOnlyAfterTheInterval() {
        PendingMessages<String> pending = new PendingMessages<>();
        pending.sent("a", "A", T0);

        assertThat(pending.dueForResend(T0.plusMillis(500), INTERVAL)).isEmpty();
        assertThat(pending.dueForResend(T0.plusSeconds(1), INTERVAL)).containsExactly("A");
        // 방금 다시 보냈으므로 다음 주기까지 기다린다
        assertThat(pending.dueForResend(T0.plusMillis(1500), INTERVAL)).isEmpty();
        assertThat(pending.dueForResend(T0.plusSeconds(2), INTERVAL)).containsExactly("A");
    }
}

package com.factoryflow.simulator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 받은 eventId 를 최근 limit 개만 기억한다. */
class SeenEventsTest {

    @Test
    void sameEventIsSeenOnceAndTheOldestIsForgotten() {
        SeenEvents seen = new SeenEvents();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertThat(seen.markIfNew(first, 2)).isTrue();
        assertThat(seen.markIfNew(first, 2)).isFalse();
        assertThat(seen.markIfNew(second, 2)).isTrue();
        assertThat(seen.markIfNew(third, 2)).isTrue();

        assertThat(seen.markIfNew(first, 2)).isTrue();
        assertThat(seen.markIfNew(third, 2)).isFalse();
    }
}

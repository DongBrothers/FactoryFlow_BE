package com.factoryflow.common.web.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CursorPageTest {

    @Test
    void extraRowMeansMorePagesAndIsDropped() {
        CursorPage<Long> page = CursorPage.of(List.of(1L, 2L, 3L), 2, Function.identity());

        assertThat(page.items()).containsExactly(1L, 2L);
        assertThat(page.count()).isEqualTo(2);
        assertThat(page.last()).isFalse();
        assertThat(page.nextCursor()).isEqualTo(2L);
    }

    @Test
    void noExtraRowIsLastPage() {
        CursorPage<Long> page = CursorPage.of(List.of(5L, 6L), 2, Function.identity());

        assertThat(page.items()).containsExactly(5L, 6L);
        assertThat(page.count()).isEqualTo(2);
        assertThat(page.last()).isTrue();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void emptyIsLastPage() {
        CursorPage<Long> page = CursorPage.of(List.of(), 2, Function.identity());

        assertThat(page.items()).isEmpty();
        assertThat(page.count()).isZero();
        assertThat(page.last()).isTrue();
        assertThat(page.nextCursor()).isNull();
    }
}

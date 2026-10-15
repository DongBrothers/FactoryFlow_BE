package com.factoryflow.common.web.response;

import java.util.List;
import java.util.function.Function;

/**
 * id 커서 페이지. 목록은 한 번에 다 읽지 않고 {@code id > lastId ORDER BY id LIMIT size} 로 나눠 읽는다. 다음 페이지는
 * nextCursor 를 lastId 로 넘겨 받는다.
 *
 * @param items 이번 페이지 항목 (id 오름차순)
 * @param count items 개수
 * @param last 마지막 페이지인지. true 면 nextCursor 는 null
 * @param nextCursor 다음 페이지 요청에 넣을 lastId (이번 페이지 마지막 항목의 id)
 */
public record CursorPage<T>(List<T> items, int count, boolean last, Long nextCursor) {

    /** size 보다 하나 더 읽은 rows 로 만든다 (LIMIT size + 1). 하나가 남으면 다음 페이지가 있다는 뜻이고, 남은 하나는 응답에서 뺀다. */
    public static <T> CursorPage<T> of(List<T> rows, int size, Function<T, Long> idOf) {
        boolean last = rows.size() <= size;
        List<T> items = last ? List.copyOf(rows) : List.copyOf(rows.subList(0, size));
        Long nextCursor = last ? null : idOf.apply(items.get(items.size() - 1));
        return new CursorPage<>(items, items.size(), last, nextCursor);
    }
}

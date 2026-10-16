package com.factoryflow.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "주문 목록 한 페이지 (최신 먼저). 다음 페이지는 nextOrderNo 를 lastOrderNo 로 넘긴다")
public record OrderListResponse(
        @Schema(description = "이번 페이지 주문") List<OrderSummaryResponse> items,
        @Schema(description = "items 개수", example = "20") int count,
        @Schema(description = "마지막 페이지인지", example = "false") boolean last,
        @Schema(
                        description = "다음 페이지 요청에 넣을 lastOrderNo. 마지막 페이지면 없음",
                        example = "550e8400-e29b-41d4-a716-446655440000")
                String nextOrderNo) {

    /** size 보다 하나 더 읽은 rows 로 만든다. 하나가 남으면 다음 페이지가 있다는 뜻이고, 남은 하나는 응답에서 뺀다. */
    public static OrderListResponse of(List<OrderSummaryResponse> rows, int size) {
        boolean last = rows.size() <= size;
        List<OrderSummaryResponse> items =
                last ? List.copyOf(rows) : List.copyOf(rows.subList(0, size));
        String nextOrderNo = last ? null : items.get(items.size() - 1).orderNo();
        return new OrderListResponse(items, items.size(), last, nextOrderNo);
    }
}

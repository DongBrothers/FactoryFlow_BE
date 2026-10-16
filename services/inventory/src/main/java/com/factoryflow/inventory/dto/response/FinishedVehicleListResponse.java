package com.factoryflow.inventory.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "완성차 목록 한 페이지 (최신 먼저). 다음 페이지는 nextVin 을 lastVin 으로 넘긴다")
public record FinishedVehicleListResponse(
        @Schema(description = "이번 페이지 완성차") List<FinishedVehicleSummaryResponse> items,
        @Schema(description = "items 개수", example = "20") int count,
        @Schema(description = "마지막 페이지인지", example = "false") boolean last,
        @Schema(description = "다음 페이지 요청에 넣을 lastVin. 마지막 페이지면 없음", example = "KMH000017TU000012")
                String nextVin) {

    /** size 보다 하나 더 읽은 rows 로 만든다. 하나가 남으면 다음 페이지가 있다는 뜻이고, 남은 하나는 응답에서 뺀다. */
    public static FinishedVehicleListResponse of(
            List<FinishedVehicleSummaryResponse> rows, int size) {
        boolean last = rows.size() <= size;
        List<FinishedVehicleSummaryResponse> items =
                last ? List.copyOf(rows) : List.copyOf(rows.subList(0, size));
        String nextVin = last ? null : items.get(items.size() - 1).vin();
        return new FinishedVehicleListResponse(items, items.size(), last, nextVin);
    }
}

package com.factoryflow.inventory.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "완성차 출고장 자리 현황 (자리 코드 순, 전체)")
public record StorageSlotListResponse(
        @Schema(description = "자리 전체") List<StorageSlotResponse> items,
        @Schema(description = "전체 자리 수", example = "40") int total,
        @Schema(description = "차가 서 있는 자리 수", example = "12") int occupied,
        @Schema(description = "자리가 없어 기다리는 창고의 완성차 수", example = "0") long waitingVehicles) {

    public static StorageSlotListResponse of(
            List<StorageSlotResponse> items, int occupied, long waitingVehicles) {
        return new StorageSlotListResponse(items, items.size(), occupied, waitingVehicles);
    }
}

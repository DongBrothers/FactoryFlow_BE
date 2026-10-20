package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.StorageSlot;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "완성차 출고장 자리 하나")
public record StorageSlotResponse(
        @Schema(description = "자리 코드", example = "A-01") String slotCode,
        @Schema(description = "구역", example = "A") String zone,
        @Schema(description = "서 있는 완성차의 차대번호. 비었으면 없음", example = "KMH000017TU000012")
                String vin) {

    public static StorageSlotResponse of(StorageSlot slot, String vin) {
        return new StorageSlotResponse(slot.getCode(), slot.getZone(), vin);
    }
}

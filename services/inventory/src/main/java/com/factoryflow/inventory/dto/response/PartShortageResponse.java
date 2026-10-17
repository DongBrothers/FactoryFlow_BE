package com.factoryflow.inventory.dto.response;

import com.factoryflow.inventory.domain.Part;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "부족한 부품: 기다리는 수량이 바로 쓸 수 있는 수량보다 많다")
public record PartShortageResponse(
        @Schema(description = "부품 id (구매 건 만들기에 넣는다)", example = "1012") Long partId,
        @Schema(description = "부품 코드", example = "TIRE-18") String code,
        @Schema(description = "부품 이름", example = "타이어 18인치") String name,
        @Schema(description = "단위 (EA, SET, KG)", example = "EA") String unit,
        @Schema(description = "바로 쓸 수 있는 수량", example = "3") int available,
        @Schema(description = "기다리는 예약 줄 수량 합", example = "6") int waitingQty,
        @Schema(description = "부족 수량 (기다리는 수량 − 바로 쓸 수 있는 수량). 이미 발주한 수량은 빼지 않는다", example = "3")
                int shortQty,
        @Schema(description = "이 부품을 기다리는 생산 건 수", example = "1") int waitingProductions) {

    public static PartShortageResponse of(Part part, int waitingQty, int waitingProductions) {
        return new PartShortageResponse(
                part.getId(),
                part.getCode(),
                part.getName(),
                part.getUnit(),
                part.getAvailable(),
                waitingQty,
                waitingQty - part.getAvailable(),
                waitingProductions);
    }
}

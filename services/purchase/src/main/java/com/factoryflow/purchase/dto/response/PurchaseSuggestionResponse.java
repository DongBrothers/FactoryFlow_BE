package com.factoryflow.purchase.dto.response;

import com.factoryflow.purchase.client.inventory.dto.response.PartShortageResponse;
import com.factoryflow.purchase.domain.Supplier;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "발주 제안 한 줄: 부족 수량에서 발주 중 수량을 뺀 만큼 더 산다")
public record PurchaseSuggestionResponse(
        @Schema(description = "부품 id (구매 건 만들기에 넣는다)", example = "1012") Long partId,
        @Schema(description = "부품 코드", example = "TIRE-18") String partCode,
        @Schema(description = "부품 이름", example = "타이어 18인치") String partName,
        @Schema(description = "단위 (EA, SET, KG)", example = "EA") String unit,
        @Schema(description = "바로 쓸 수 있는 수량", example = "3") int available,
        @Schema(description = "기다리는 예약 줄 수량 합", example = "20") int waitingQty,
        @Schema(description = "부족 수량 (기다리는 수량 − 바로 쓸 수 있는 수량)", example = "17") int shortQty,
        @Schema(description = "발주했지만 아직 입고되지 않은(ORDERED) 수량 합", example = "10") long orderedQty,
        @Schema(description = "더 사야 할 수량 = max(0, 부족 수량 − 발주 중 수량). 0 이면 이미 오고 있다", example = "7")
                long suggestedQty,
        @Schema(description = "가장 최근 구매 건의 공급업체 id (산 적 없으면 없음)", example = "2")
                Long lastSupplierId,
        @Schema(description = "가장 최근 구매 건의 공급업체 이름 (산 적 없으면 없음)", example = "한국타이어")
                String lastSupplierName) {

    public static PurchaseSuggestionResponse of(
            PartShortageResponse shortage, long orderedQty, Supplier lastSupplier) {
        return new PurchaseSuggestionResponse(
                shortage.partId(),
                shortage.code(),
                shortage.name(),
                shortage.unit(),
                shortage.available(),
                shortage.waitingQty(),
                shortage.shortQty(),
                orderedQty,
                Math.max(0, shortage.shortQty() - orderedQty),
                lastSupplier == null ? null : lastSupplier.getId(),
                lastSupplier == null ? null : lastSupplier.getName());
    }
}

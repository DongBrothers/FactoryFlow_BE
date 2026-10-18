package com.factoryflow.purchase.dto.response;

import com.factoryflow.purchase.domain.Purchase;
import com.factoryflow.purchase.domain.PurchaseStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "구매 건")
public record PurchaseResponse(
        @Schema(description = "구매 번호", example = "31") Long purchaseId,
        @Schema(description = "부품 번호", example = "1012") Long partId,
        @Schema(description = "부품 코드 (발주 당시)", example = "TIRE-18") String partCode,
        @Schema(description = "부품 이름 (발주 당시)", example = "타이어 18인치") String partName,
        @Schema(description = "수량", example = "6") int qty,
        @Schema(description = "상태 (ORDERED, RECEIVED, CANCELLED)", example = "ORDERED")
                PurchaseStatus status,
        @Schema(description = "발주한 공급업체 번호", example = "2") Long supplierId,
        @Schema(description = "발주한 직원 번호 (public_id). 자동 요청 시절 건은 없음", example = "6c1e2f3a-…")
                String orderedBy,
        @Schema(description = "발주 시각", example = "2026-10-16T09:00:00") LocalDateTime orderedAt,
        @Schema(description = "입고 시각", example = "2026-10-17T14:00:00") LocalDateTime receivedAt) {

    public static PurchaseResponse from(Purchase purchase) {
        return new PurchaseResponse(
                purchase.getId(),
                purchase.getPartId(),
                purchase.getPartCode(),
                purchase.getPartName(),
                purchase.getQty(),
                purchase.getStatus(),
                purchase.getSupplierId(),
                purchase.getOrderedBy(),
                purchase.getOrderedAt(),
                purchase.getReceivedAt());
    }
}

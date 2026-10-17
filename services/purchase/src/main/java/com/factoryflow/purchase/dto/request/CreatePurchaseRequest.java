package com.factoryflow.purchase.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "구매 건 만들기 = 발주. 부족 부품 조회(GET /inventory/parts/shortages)를 보고 넣는다")
public record CreatePurchaseRequest(
        @Schema(description = "부품 번호 (inventory)", example = "1012")
                @NotNull(message = "부품 번호가 필요합니다.")
                @Positive(message = "부품 번호는 1 이상이어야 합니다.")
                Long partId,
        @Schema(description = "수량 (부족분보다 많이 살 수 있다)", example = "10")
                @NotNull(message = "수량이 필요합니다.")
                @Positive(message = "수량은 1 이상이어야 합니다.")
                Integer qty,
        @Schema(description = "공급업체 번호", example = "2")
                @NotNull(message = "공급업체를 골라야 합니다.")
                @Positive(message = "공급업체 번호는 1 이상이어야 합니다.")
                Long supplierId) {}

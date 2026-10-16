package com.factoryflow.purchase.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "발주 요청")
public record OrderPurchaseRequest(
        @Schema(description = "발주할 공급업체 번호", example = "1")
                @NotNull(message = "공급업체를 지정해야 합니다.")
                @Positive(message = "공급업체 번호는 1 이상이어야 합니다.")
                Long supplierId) {}

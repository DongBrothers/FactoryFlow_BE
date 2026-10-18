package com.factoryflow.purchase.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "공급업체 등록 요청")
public record CreateSupplierRequest(
        @Schema(description = "이름 (100자 이하)", example = "넥센타이어")
                @NotBlank(message = "공급업체 이름이 필요합니다.")
                @Size(max = 100, message = "공급업체 이름은 100자 이하여야 합니다.")
                String name,
        @Schema(description = "연락처 (100자 이하, 없어도 됨)", example = "02-0000-0004")
                @Size(max = 100, message = "연락처는 100자 이하여야 합니다.")
                String contact) {}

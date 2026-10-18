package com.factoryflow.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "고객 등록 요청")
public record CreateCustomerRequest(
        @Schema(description = "고객 이름 (50자 이하)", example = "현대캐피탈")
                @NotBlank(message = "고객 이름이 필요합니다.")
                @Size(max = 50, message = "고객 이름은 50자 이하여야 합니다.")
                String name,
        @Schema(description = "연락처 (100자 이하, 없어도 됨)", example = "02-0000-0000")
                @Size(max = 100, message = "연락처는 100자 이하여야 합니다.")
                String contact) {}

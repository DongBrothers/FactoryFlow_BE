package com.factoryflow.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "부품 수정 요청. 코드와 수량은 바꾸지 않는다")
public record UpdatePartRequest(
        @Schema(description = "부품 이름 (100자 이하)", example = "타이어 19인치 (사계절)")
                @NotBlank(message = "부품 이름이 필요합니다.")
                @Size(max = 100, message = "부품 이름은 100자 이하여야 합니다.")
                String name,
        @Schema(description = "단위 (EA, SET, KG)", example = "EA")
                @NotBlank(message = "단위가 필요합니다.")
                @Pattern(regexp = "EA|SET|KG", message = "단위는 EA, SET, KG 중 하나여야 합니다.")
                String unit) {}

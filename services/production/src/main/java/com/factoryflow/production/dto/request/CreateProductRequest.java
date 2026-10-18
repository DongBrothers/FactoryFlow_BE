package com.factoryflow.production.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "제품 등록 요청")
public record CreateProductRequest(
        @Schema(description = "제품 코드 (50자 이하, 나중에 바꿀 수 없음)", example = "IONIQ5")
                @NotBlank(message = "제품 코드가 필요합니다.")
                @Size(max = 50, message = "제품 코드는 50자 이하여야 합니다.")
                String code,
        @Schema(description = "제품 이름 (100자 이하)", example = "아이오닉 5")
                @NotBlank(message = "제품 이름이 필요합니다.")
                @Size(max = 100, message = "제품 이름은 100자 이하여야 합니다.")
                String name,
        @Schema(description = "BOM (1줄 이상, 같은 부품은 한 번만)")
                @NotEmpty(message = "BOM 에 부품을 하나 이상 넣어야 합니다.")
                @UniquePartIds
                List<@NotNull @Valid BomItemRequest> bom) {}

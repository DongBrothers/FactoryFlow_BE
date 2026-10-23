package com.factoryflow.mes.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "의장 공정에서 찍은 부품 LOT 하나 (부품마다 하나)")
public record ScannedLotRequest(
        @Schema(description = "부품 id (inventory)", example = "1001")
                @NotNull(message = "partId 가 필요합니다.")
                @Positive(message = "partId 는 1 이상이어야 합니다.")
                Long partId,
        @Schema(description = "부품 LOT 번호 (바코드)", example = "LOT-261017-000012")
                @NotNull(message = "lotNo 가 필요합니다.")
                @Size(max = 32, message = "lotNo 는 32자 이하입니다.")
                @Pattern(regexp = "LOT-\\S+", message = "lotNo 는 LOT- 로 시작하고 공백이 없어야 합니다.")
                String lotNo) {}

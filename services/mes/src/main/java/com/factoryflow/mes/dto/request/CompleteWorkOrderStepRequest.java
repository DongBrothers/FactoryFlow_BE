package com.factoryflow.mes.dto.request;

import com.factoryflow.mes.domain.StepResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "공정 종료(실적) 요청. 불량이면 사유가 필요하다")
@DefectReasonRequired
public record CompleteWorkOrderStepRequest(
        @Schema(description = "실적 결과 (GOOD: 양품, DEFECT: 불량)", example = "GOOD")
                @NotNull(message = "실적 결과를 넣어야 합니다.")
                StepResult result,
        @Schema(description = "불량 사유 (DEFECT 일 때 필수)", example = "도장 얼룩")
                @Size(max = 255, message = "불량 사유는 255자 이하입니다.")
                String defectReason,
        @Schema(description = "의장 공정 양품 때 찍은 부품 LOT (선택, 최대 50개, 부품마다 하나). 다른 공정·불량이면 넣지 않는다")
                @Size(max = 50, message = "찍은 LOT 은 50개 이하입니다.")
                @UniqueScannedParts
                List<@NotNull @Valid ScannedLotRequest> scannedLots) {

    /** 보내지 않았으면 빈 목록. */
    public List<ScannedLotRequest> scannedLotsOrEmpty() {
        return scannedLots == null ? List.of() : scannedLots;
    }
}

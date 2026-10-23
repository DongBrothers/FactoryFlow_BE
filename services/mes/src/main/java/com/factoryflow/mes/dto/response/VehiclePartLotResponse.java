package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.WorkOrderPartScan;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "차에 들어간 부품 LOT 하나 (의장에서 찍은 것)")
public record VehiclePartLotResponse(
        @Schema(description = "부품 id (이름은 inventory 부품 목록으로)", example = "1001") Long partId,
        @Schema(description = "LOT 번호", example = "LOT-261017-000012") String lotNo,
        @Schema(description = "찍은 작업자 public_id", example = "3f2a9c1e-0000-4000-8000-000000000001")
                String scannedBy,
        @Schema(description = "찍은 시각", example = "2026-10-23T22:09:09") LocalDateTime scannedAt) {

    public static VehiclePartLotResponse from(WorkOrderPartScan scan) {
        return new VehiclePartLotResponse(
                scan.getPartId(), scan.getLotNo(), scan.getScannedBy(), scan.getScannedAt());
    }
}

package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.WorkOrder;
import com.factoryflow.mes.domain.WorkOrderPartScan;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "LOT 의 부품이 들어간 차 한 대 (리콜 대상)")
public record LotVehicleResponse(
        @Schema(description = "작업지시 번호", example = "12") Long workOrderId,
        @Schema(description = "차대번호. 검사 전이면 없음", example = "KMH000013TU000012") String vin,
        @Schema(description = "제품 id", example = "1") Long productId,
        @Schema(description = "부품 id", example = "1001") Long partId,
        @Schema(description = "찍은 시각", example = "2026-10-23T22:09:09") LocalDateTime scannedAt) {

    public static LotVehicleResponse of(WorkOrderPartScan scan, WorkOrder workOrder) {
        return new LotVehicleResponse(
                workOrder.getId(),
                workOrder.getVin(),
                workOrder.getProductId(),
                scan.getPartId(),
                scan.getScannedAt());
    }
}

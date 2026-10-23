package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.EquipmentStatus;
import com.factoryflow.mes.domain.EquipmentStatusHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "설비 상태 이력 한 줄")
public record EquipmentStatusHistoryResponse(
        @Schema(description = "바뀌기 전 상태", example = "RUNNING") EquipmentStatus fromStatus,
        @Schema(description = "바뀐 상태", example = "FAULT") EquipmentStatus toStatus,
        @Schema(description = "상태가 바뀐 시각 (설비가 보낸 이벤트 시각)", example = "2026-10-24T10:00:00")
                LocalDateTime changedAt) {

    public static EquipmentStatusHistoryResponse from(EquipmentStatusHistory history) {
        return new EquipmentStatusHistoryResponse(
                history.getFromStatus(), history.getToStatus(), history.getChangedAt());
    }
}

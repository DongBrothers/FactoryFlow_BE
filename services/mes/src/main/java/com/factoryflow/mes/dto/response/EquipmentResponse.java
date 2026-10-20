package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.Equipment;
import com.factoryflow.mes.domain.EquipmentStatus;
import com.factoryflow.mes.domain.ProductionProcess;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "설비")
public record EquipmentResponse(
        @Schema(description = "설비 코드", example = "PRESS-01") String equipmentCode,
        @Schema(description = "설비 이름", example = "프레스기 1호") String name,
        @Schema(description = "공정", example = "PRESS") ProductionProcess process,
        @Schema(description = "설비 상태 (RUNNING 일 때만 공정을 시작할 수 있다)", example = "RUNNING")
                EquipmentStatus status) {

    public static EquipmentResponse from(Equipment equipment) {
        return new EquipmentResponse(
                equipment.getCode(),
                equipment.getName(),
                equipment.getProcess(),
                equipment.getStatus());
    }
}

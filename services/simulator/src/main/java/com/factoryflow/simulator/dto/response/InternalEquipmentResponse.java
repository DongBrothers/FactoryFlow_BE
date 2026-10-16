package com.factoryflow.simulator.dto.response;

import com.factoryflow.simulator.domain.EquipmentStatus;
import com.factoryflow.simulator.domain.LineProcess;
import com.factoryflow.simulator.domain.VirtualEquipment;

/** 가상 설비 상태 (내부 API). 작업 중이 아니면 workOrderId 는 null. */
public record InternalEquipmentResponse(
        String equipmentCode, LineProcess process, EquipmentStatus status, Long workOrderId) {

    public static InternalEquipmentResponse from(VirtualEquipment equipment) {
        return new InternalEquipmentResponse(
                equipment.getCode(),
                equipment.getProcess(),
                equipment.getStatus(),
                equipment.getJob() == null ? null : equipment.getJob().workOrderId());
    }
}

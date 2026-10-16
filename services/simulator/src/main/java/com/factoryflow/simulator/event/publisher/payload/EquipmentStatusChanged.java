package com.factoryflow.simulator.event.publisher.payload;

import com.factoryflow.simulator.domain.EquipmentStatus;
import com.factoryflow.simulator.domain.LineProcess;

/** 설비 상태가 바뀌었다 (equipment.status-changed). */
public record EquipmentStatusChanged(
        String equipmentCode, LineProcess process, EquipmentStatus status)
        implements EquipmentEvent {

    @Override
    public String eventName() {
        return "equipment.status-changed";
    }
}

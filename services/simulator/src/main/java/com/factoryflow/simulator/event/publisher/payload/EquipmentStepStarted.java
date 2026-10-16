package com.factoryflow.simulator.event.publisher.payload;

import com.factoryflow.simulator.domain.LineProcess;

/** 설비가 배정받은 공정을 시작했다 (equipment.step-started). */
public record EquipmentStepStarted(String equipmentCode, Long workOrderId, LineProcess process)
        implements EquipmentEvent {

    @Override
    public String eventName() {
        return "equipment.step-started";
    }
}

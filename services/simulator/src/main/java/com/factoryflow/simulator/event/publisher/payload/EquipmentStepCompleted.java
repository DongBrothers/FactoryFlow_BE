package com.factoryflow.simulator.event.publisher.payload;

import com.factoryflow.simulator.domain.LineProcess;
import com.factoryflow.simulator.domain.StepResult;

/** 설비가 공정을 끝냈다 (equipment.step-completed). */
public record EquipmentStepCompleted(
        String equipmentCode,
        Long workOrderId,
        LineProcess process,
        StepResult result,
        String defectReason)
        implements EquipmentEvent {

    @Override
    public String eventName() {
        return "equipment.step-completed";
    }
}

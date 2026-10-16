package com.factoryflow.simulator.event.publisher.payload;

import com.factoryflow.simulator.domain.LineProcess;

/** 설비가 공정을 끝냈다 (equipment.step-completed). result 는 GOOD 또는 DEFECT. */
public record EquipmentStepCompleted(
        String equipmentCode,
        Long workOrderId,
        LineProcess process,
        String result,
        String defectReason) {}

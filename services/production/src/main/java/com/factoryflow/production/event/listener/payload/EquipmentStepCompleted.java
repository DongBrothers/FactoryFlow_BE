package com.factoryflow.production.event.listener.payload;

import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.domain.StepResult;

/** 설비가 공정을 끝냈다. 결과는 양품 또는 불량(사유). */
public record EquipmentStepCompleted(
        String equipmentCode,
        Long workOrderId,
        ProductionProcess process,
        StepResult result,
        String defectReason,
        Integer dispatchSeq) {}

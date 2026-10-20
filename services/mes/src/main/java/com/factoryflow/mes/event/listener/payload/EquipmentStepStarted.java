package com.factoryflow.mes.event.listener.payload;

import com.factoryflow.mes.domain.ProductionProcess;

/** 설비가 배정받은 공정을 시작했다. */
public record EquipmentStepStarted(
        String equipmentCode, Long workOrderId, ProductionProcess process, Integer dispatchSeq) {}

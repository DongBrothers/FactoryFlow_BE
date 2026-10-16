package com.factoryflow.production.event.listener.payload;

import com.factoryflow.production.domain.EquipmentStatus;
import com.factoryflow.production.domain.ProductionProcess;

/** 설비 상태가 바뀌었다 (가동·정지·고장). process 는 참고용이고 설비는 equipmentCode 로 찾는다. */
public record EquipmentStatusChanged(
        String equipmentCode, ProductionProcess process, EquipmentStatus status) {}

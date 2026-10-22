package com.factoryflow.mes.event.listener.payload;

import com.factoryflow.mes.domain.ProductionProcess;
import java.util.List;

/**
 * 받은 equipment.step-telemetry 의 payload (docs/specs/process-telemetry.md). 로봇 공정 하나를 하는 동안 설비가 잰 값.
 */
public record EquipmentStepTelemetry(
        String equipmentCode,
        Long workOrderId,
        ProductionProcess process,
        Integer dispatchSeq,
        List<TelemetrySample> samples) {}

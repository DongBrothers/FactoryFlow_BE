package com.factoryflow.simulator.event.publisher.payload;

import com.factoryflow.simulator.domain.LineProcess;
import java.util.List;

/**
 * 로봇 공정 하나를 하는 동안 잰 값 (equipment.step-telemetry, docs/specs/process-telemetry.md). 완료 보고 직후 한 번
 * 보낸다.
 */
public record EquipmentStepTelemetry(
        String equipmentCode,
        Long workOrderId,
        LineProcess process,
        Integer dispatchSeq,
        List<TelemetrySample> samples)
        implements EquipmentEvent {

    @Override
    public String eventName() {
        return "equipment.step-telemetry";
    }
}

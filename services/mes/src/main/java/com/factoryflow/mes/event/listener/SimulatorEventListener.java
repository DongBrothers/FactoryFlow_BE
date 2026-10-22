package com.factoryflow.mes.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.mes.config.EventQueueConfig;
import com.factoryflow.mes.event.listener.payload.EquipmentStatusChanged;
import com.factoryflow.mes.event.listener.payload.EquipmentStepCompleted;
import com.factoryflow.mes.event.listener.payload.EquipmentStepStarted;
import com.factoryflow.mes.event.listener.payload.EquipmentStepTelemetry;
import com.factoryflow.mes.service.EquipmentService;
import com.factoryflow.mes.service.TelemetryService;
import com.factoryflow.mes.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 가상 설비(simulator)의 로봇 공정 보고 (docs/specs/production-work-order.md "3. 로봇 공정 자동 진행"). */
@Component
@RequiredArgsConstructor
public class SimulatorEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final WorkOrderService workOrderService;
    private final EquipmentService equipmentService;
    private final TelemetryService telemetryService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = EventQueueConfig.EQUIPMENT_STEP_STARTED_QUEUE)
    public void onStepStarted(EventEnvelope envelope) {
        EquipmentStepStarted event =
                objectMapper.treeToValue(envelope.payload(), EquipmentStepStarted.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        workOrderService.reportStepStarted(
                                event.workOrderId(),
                                event.process(),
                                event.equipmentCode(),
                                event.dispatchSeq()));
    }

    @RabbitListener(queues = EventQueueConfig.EQUIPMENT_STEP_COMPLETED_QUEUE)
    public void onStepCompleted(EventEnvelope envelope) {
        EquipmentStepCompleted event =
                objectMapper.treeToValue(envelope.payload(), EquipmentStepCompleted.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        workOrderService.reportStepCompleted(
                                event.workOrderId(),
                                event.process(),
                                event.equipmentCode(),
                                event.result(),
                                event.defectReason(),
                                event.dispatchSeq()));
    }

    /** 설비 상태 변경: 상태·이력 저장, 고장·정지면 그 설비의 공정을 되돌려 다른 설비로 재배정하게 한다. */
    @RabbitListener(queues = EventQueueConfig.EQUIPMENT_STATUS_CHANGED_QUEUE)
    public void onStatusChanged(EventEnvelope envelope) {
        EquipmentStatusChanged event =
                objectMapper.treeToValue(envelope.payload(), EquipmentStatusChanged.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        equipmentService.changeStatus(
                                event.equipmentCode(), event.status(), envelope.occurredAt()));
    }

    /** 로봇 공정 중 센서값: MongoDB 에 저장한다. 공정 진행과 큐가 달라 저장이 늦거나 실패해도 공정에 영향이 없다. */
    @RabbitListener(queues = EventQueueConfig.EQUIPMENT_STEP_TELEMETRY_QUEUE)
    public void onStepTelemetry(EventEnvelope envelope) {
        EquipmentStepTelemetry event =
                objectMapper.treeToValue(envelope.payload(), EquipmentStepTelemetry.class);
        processedEventGuard.runOnce(envelope, () -> telemetryService.record(event));
    }
}

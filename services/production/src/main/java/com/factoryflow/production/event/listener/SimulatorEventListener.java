package com.factoryflow.production.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.production.config.EventQueueConfig;
import com.factoryflow.production.event.listener.payload.EquipmentStepCompleted;
import com.factoryflow.production.event.listener.payload.EquipmentStepStarted;
import com.factoryflow.production.service.WorkOrderService;
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
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = EventQueueConfig.EQUIPMENT_STEP_STARTED_QUEUE)
    public void onStepStarted(EventEnvelope envelope) {
        EquipmentStepStarted event =
                objectMapper.treeToValue(envelope.payload(), EquipmentStepStarted.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        workOrderService.reportStepStarted(
                                event.workOrderId(), event.process(), event.equipmentCode()));
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
                                event.defectReason()));
    }
}

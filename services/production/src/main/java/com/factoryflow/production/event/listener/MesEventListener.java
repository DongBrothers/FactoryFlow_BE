package com.factoryflow.production.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.production.config.EventQueueConfig;
import com.factoryflow.production.domain.ProductionStatus;
import com.factoryflow.production.event.listener.payload.MesProductionStarted;
import com.factoryflow.production.event.listener.payload.MesVehicleCompleted;
import com.factoryflow.production.event.listener.payload.MesWorkOrderCancelRejected;
import com.factoryflow.production.event.listener.payload.MesWorkOrderCancelled;
import com.factoryflow.production.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** mes 가 알려 주는 현장 결과: 생산 시작, 완성, 발행한 차의 취소·거절 (docs/specs/mes-split.md). */
@Component
@RequiredArgsConstructor
public class MesEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final ProductionService productionService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = EventQueueConfig.MES_PRODUCTION_STARTED_QUEUE)
    public void onProductionStarted(EventEnvelope envelope) {
        MesProductionStarted event =
                objectMapper.treeToValue(envelope.payload(), MesProductionStarted.class);
        processedEventGuard.runOnce(
                envelope, () -> productionService.startProduction(event.productionId()));
    }

    @RabbitListener(queues = EventQueueConfig.MES_VEHICLE_COMPLETED_QUEUE)
    public void onVehicleCompleted(EventEnvelope envelope) {
        MesVehicleCompleted event =
                objectMapper.treeToValue(envelope.payload(), MesVehicleCompleted.class);
        processedEventGuard.runOnce(
                envelope,
                () -> productionService.completeProduction(event.productionId(), event.vin()));
    }

    @RabbitListener(queues = EventQueueConfig.MES_WORK_ORDER_CANCELLED_QUEUE)
    public void onWorkOrderCancelled(EventEnvelope envelope) {
        MesWorkOrderCancelled event =
                objectMapper.treeToValue(envelope.payload(), MesWorkOrderCancelled.class);
        processedEventGuard.runOnce(
                envelope, () -> productionService.cancelReleased(event.productionId()));
    }

    @RabbitListener(queues = EventQueueConfig.MES_WORK_ORDER_CANCEL_REJECTED_QUEUE)
    public void onWorkOrderCancelRejected(EventEnvelope envelope) {
        MesWorkOrderCancelRejected event =
                objectMapper.treeToValue(envelope.payload(), MesWorkOrderCancelRejected.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        productionService.rejectReleasedCancel(
                                event.productionId(), ProductionStatus.valueOf(event.status())));
    }
}

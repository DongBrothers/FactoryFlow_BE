package com.factoryflow.mes.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.mes.config.EventQueueConfig;
import com.factoryflow.mes.event.listener.payload.ProductionReleased;
import com.factoryflow.mes.event.listener.payload.ReleaseCancelRequested;
import com.factoryflow.mes.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** production 이 넘긴 작업지시 발행과 발행한 차의 취소 요청 (docs/specs/mes-split.md). */
@Component
@RequiredArgsConstructor
public class ProductionEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final WorkOrderService workOrderService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = EventQueueConfig.PRODUCTION_RELEASED_QUEUE)
    public void onReleased(EventEnvelope envelope) {
        ProductionReleased event =
                objectMapper.treeToValue(envelope.payload(), ProductionReleased.class);
        processedEventGuard.runOnce(envelope, () -> workOrderService.release(event));
    }

    @RabbitListener(queues = EventQueueConfig.RELEASE_CANCEL_REQUESTED_QUEUE)
    public void onReleaseCancelRequested(EventEnvelope envelope) {
        ReleaseCancelRequested event =
                objectMapper.treeToValue(envelope.payload(), ReleaseCancelRequested.class);
        processedEventGuard.runOnce(
                envelope,
                () -> workOrderService.cancelRelease(event.productionId(), event.orderId()));
    }
}

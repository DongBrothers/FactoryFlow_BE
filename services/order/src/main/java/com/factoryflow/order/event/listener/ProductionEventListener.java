package com.factoryflow.order.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.order.config.EventQueueConfig;
import com.factoryflow.order.service.OrderItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * production 의 생산 건 상태 변화를 받는다. 처리 기록과 상태 갱신은 한 트랜잭션이고, 받은 traceId 는 ProcessedEventGuard 가 이어 준다.
 */
@Component
@RequiredArgsConstructor
public class ProductionEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final OrderItemService orderItemService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = EventQueueConfig.STATUS_CHANGED_QUEUE)
    public void onStatusChanged(EventEnvelope envelope) {
        ProductionStatusChanged event =
                objectMapper.treeToValue(envelope.payload(), ProductionStatusChanged.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        orderItemService.applyProductionStatus(
                                event.orderId(), event.productId(), event.status()));
    }
}

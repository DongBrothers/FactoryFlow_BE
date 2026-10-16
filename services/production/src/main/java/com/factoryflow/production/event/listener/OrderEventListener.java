package com.factoryflow.production.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.production.config.EventQueueConfig;
import com.factoryflow.production.event.listener.payload.OrderCancelRequested;
import com.factoryflow.production.event.listener.payload.OrderCreated;
import com.factoryflow.production.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final ProductionService productionService;
    private final ObjectMapper objectMapper;

    /**
     * 처리 기록과 생산 건 생성은 한 트랜잭션이다. 실패하면 둘 다 롤백되고 RabbitMQ 가 다시 보낸다. 받은 traceId 는 ProcessedEventGuard 가
     * 이어 준다.
     */
    @RabbitListener(queues = EventQueueConfig.ORDER_CREATED_QUEUE)
    public void onOrderCreated(EventEnvelope envelope) {
        OrderCreated event = objectMapper.treeToValue(envelope.payload(), OrderCreated.class);
        processedEventGuard.runOnce(
                envelope, () -> productionService.planAll(event.orderId(), event.products()));
    }

    /** 주문 취소 요청: 생산 건마다 프레스 배정 전이면 취소, 아니면 거절. 처리 기록과 같은 트랜잭션이다. */
    @RabbitListener(queues = EventQueueConfig.ORDER_CANCEL_REQUESTED_QUEUE)
    public void onCancelRequested(EventEnvelope envelope) {
        OrderCancelRequested event =
                objectMapper.treeToValue(envelope.payload(), OrderCancelRequested.class);
        processedEventGuard.runOnce(envelope, () -> productionService.cancelAll(event.orderId()));
    }
}

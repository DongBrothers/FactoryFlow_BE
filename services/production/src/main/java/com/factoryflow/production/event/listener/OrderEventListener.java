package com.factoryflow.production.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.production.config.EventQueueConfig;
import com.factoryflow.production.service.ProductionService;
import java.util.Map;
import java.util.stream.Collectors;
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

    /** 처리 기록과 생산 건 생성은 한 트랜잭션이다. 실패하면 둘 다 롤백되고 RabbitMQ 가 다시 보낸다. */
    @RabbitListener(queues = EventQueueConfig.ORDER_CREATED_QUEUE)
    public void onOrderCreated(EventEnvelope envelope) {
        OrderCreated event = objectMapper.treeToValue(envelope.payload(), OrderCreated.class);
        Map<Long, Integer> qtyByProductId =
                event.products().stream()
                        .collect(
                                Collectors.toMap(
                                        OrderCreatedProduct::productId, OrderCreatedProduct::qty));
        processedEventGuard.runOnce(
                envelope.eventId(),
                () -> productionService.planAll(event.orderId(), qtyByProductId));
    }
}

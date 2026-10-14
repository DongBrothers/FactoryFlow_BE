package com.factoryflow.production.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.common.web.TraceIdFilter;
import com.factoryflow.production.config.EventQueueConfig;
import com.factoryflow.production.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
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
     * 처리 기록과 생산 건 생성은 한 트랜잭션이다. 실패하면 둘 다 롤백되고 RabbitMQ 가 다시 보낸다. 처리하는 동안 받은 이벤트의 traceId 를 MDC 에
     * 두어, 이어서 발행하는 이벤트가 같은 traceId 를 쓰게 한다 (EventPublisher 가 MDC 의 traceId 를 쓴다).
     */
    @RabbitListener(queues = EventQueueConfig.ORDER_CREATED_QUEUE)
    public void onOrderCreated(EventEnvelope envelope) {
        MDC.put(TraceIdFilter.MDC_KEY, envelope.traceId());
        try {
            OrderCreated event = objectMapper.treeToValue(envelope.payload(), OrderCreated.class);
            processedEventGuard.runOnce(
                    envelope.eventId(),
                    () -> productionService.planAll(event.orderId(), event.products()));
        } finally {
            MDC.remove(TraceIdFilter.MDC_KEY);
        }
    }
}

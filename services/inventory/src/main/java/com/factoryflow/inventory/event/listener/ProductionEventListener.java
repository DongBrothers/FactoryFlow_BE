package com.factoryflow.inventory.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.common.web.TraceIdFilter;
import com.factoryflow.inventory.config.EventQueueConfig;
import com.factoryflow.inventory.service.PartReservationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class ProductionEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final PartReservationService partReservationService;
    private final ObjectMapper objectMapper;

    /**
     * 처리 기록과 예약은 한 트랜잭션이다. 실패하면 둘 다 롤백되고 RabbitMQ 가 다시 보낸다. 처리하는 동안 받은 이벤트의 traceId 를 MDC 에 두어, 이어서
     * 발행하는 이벤트가 같은 traceId 를 쓰게 한다 (EventPublisher 가 MDC 의 traceId 를 쓴다).
     */
    @RabbitListener(queues = EventQueueConfig.MATERIAL_REQUESTED_QUEUE)
    public void onMaterialRequested(EventEnvelope envelope) {
        MDC.put(TraceIdFilter.MDC_KEY, envelope.traceId());
        try {
            MaterialRequested event =
                    objectMapper.treeToValue(envelope.payload(), MaterialRequested.class);
            processedEventGuard.runOnce(
                    envelope.eventId(),
                    () -> partReservationService.reserve(event.productionId(), event.parts()));
        } finally {
            MDC.remove(TraceIdFilter.MDC_KEY);
        }
    }
}

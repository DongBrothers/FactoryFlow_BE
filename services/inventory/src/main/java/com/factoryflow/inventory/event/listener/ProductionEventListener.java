package com.factoryflow.inventory.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.inventory.config.EventQueueConfig;
import com.factoryflow.inventory.event.listener.payload.MaterialRequested;
import com.factoryflow.inventory.service.PartReservationService;
import lombok.RequiredArgsConstructor;
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
     * 처리 기록과 예약은 한 트랜잭션이다. 실패하면 둘 다 롤백되고 RabbitMQ 가 다시 보낸다. 받은 traceId 는 ProcessedEventGuard 가 이어
     * 준다.
     */
    @RabbitListener(queues = EventQueueConfig.MATERIAL_REQUESTED_QUEUE)
    public void onMaterialRequested(EventEnvelope envelope) {
        MaterialRequested event =
                objectMapper.treeToValue(envelope.payload(), MaterialRequested.class);
        processedEventGuard.runOnce(
                envelope,
                () -> partReservationService.reserve(event.productionId(), event.parts()));
    }
}

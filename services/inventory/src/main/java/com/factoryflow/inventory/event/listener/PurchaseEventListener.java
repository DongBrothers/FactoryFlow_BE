package com.factoryflow.inventory.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.inventory.config.EventQueueConfig;
import com.factoryflow.inventory.event.listener.payload.PurchaseReceived;
import com.factoryflow.inventory.service.PartReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PurchaseEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final PartReservationService partReservationService;
    private final ObjectMapper objectMapper;

    /** 입고: 재고를 늘리고 기다리던 예약 줄을 먼저 온 순서로 채운다. 처리 기록과 같은 트랜잭션이다. */
    @RabbitListener(queues = EventQueueConfig.PURCHASE_RECEIVED_QUEUE)
    public void onReceived(EventEnvelope envelope) {
        PurchaseReceived event =
                objectMapper.treeToValue(envelope.payload(), PurchaseReceived.class);
        processedEventGuard.runOnce(envelope, () -> partReservationService.receive(event));
    }
}

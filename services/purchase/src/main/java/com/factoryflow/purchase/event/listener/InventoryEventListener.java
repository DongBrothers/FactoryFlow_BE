package com.factoryflow.purchase.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.purchase.config.EventQueueConfig;
import com.factoryflow.purchase.event.listener.payload.ShortageDetected;
import com.factoryflow.purchase.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class InventoryEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final PurchaseService purchaseService;
    private final ObjectMapper objectMapper;

    /** 재고 부족: 자동 구매 건을 만들거나 같은 부품의 요청 건에 수량을 합친다. 처리 기록과 같은 트랜잭션이다. */
    @RabbitListener(queues = EventQueueConfig.SHORTAGE_QUEUE)
    public void onShortage(EventEnvelope envelope) {
        ShortageDetected event =
                objectMapper.treeToValue(envelope.payload(), ShortageDetected.class);
        processedEventGuard.runOnce(envelope, () -> purchaseService.requestForShortage(event));
    }
}

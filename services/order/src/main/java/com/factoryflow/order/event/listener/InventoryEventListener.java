package com.factoryflow.order.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.order.config.EventQueueConfig;
import com.factoryflow.order.event.listener.payload.ShipmentCompleted;
import com.factoryflow.order.service.OrderVehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** inventory 의 완성차 출고를 받는다. 처리 기록과 상태 갱신은 한 트랜잭션이고, 받은 traceId 는 ProcessedEventGuard 가 이어 준다. */
@Component
@RequiredArgsConstructor
public class InventoryEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final OrderVehicleService orderVehicleService;
    private final ObjectMapper objectMapper;

    /** 완성차 출고: 차량 SHIPPED, 품목·주문 요약 (docs/specs/shipment.md). */
    @RabbitListener(queues = EventQueueConfig.SHIPMENT_COMPLETED_QUEUE)
    public void onShipmentCompleted(EventEnvelope envelope) {
        ShipmentCompleted event =
                objectMapper.treeToValue(envelope.payload(), ShipmentCompleted.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        orderVehicleService.ship(
                                event.orderId(),
                                event.productionId(),
                                event.productId(),
                                event.vin()));
    }
}

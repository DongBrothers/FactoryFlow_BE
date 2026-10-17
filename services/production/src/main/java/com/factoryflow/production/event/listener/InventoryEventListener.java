package com.factoryflow.production.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.production.config.EventQueueConfig;
import com.factoryflow.production.event.listener.payload.InventoryReserved;
import com.factoryflow.production.event.listener.payload.ReservationFailed;
import com.factoryflow.production.event.listener.payload.ReservationPending;
import com.factoryflow.production.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** inventory 의 부품 예약 결과를 받는다. 처리 기록과 상태 변경은 한 트랜잭션이고, 받은 traceId 는 ProcessedEventGuard 가 이어 준다. */
@Component
@RequiredArgsConstructor
public class InventoryEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final ProductionService productionService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = EventQueueConfig.INVENTORY_RESERVED_QUEUE)
    public void onReserved(EventEnvelope envelope) {
        InventoryReserved event =
                objectMapper.treeToValue(envelope.payload(), InventoryReserved.class);
        processedEventGuard.runOnce(
                envelope, () -> productionService.markReserved(event.productionId()));
    }

    @RabbitListener(queues = EventQueueConfig.RESERVATION_PENDING_QUEUE)
    public void onReservationPending(EventEnvelope envelope) {
        ReservationPending event =
                objectMapper.treeToValue(envelope.payload(), ReservationPending.class);
        processedEventGuard.runOnce(
                envelope, () -> productionService.markWaitingForMaterial(event.productionId()));
    }

    /** 부품이 없어 예약할 수 없다: 생산 건 취소 (docs/specs/stalled-flows.md S18). */
    @RabbitListener(queues = EventQueueConfig.RESERVATION_FAILED_QUEUE)
    public void onReservationFailed(EventEnvelope envelope) {
        ReservationFailed event =
                objectMapper.treeToValue(envelope.payload(), ReservationFailed.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        productionService.cancelForMissingParts(
                                event.productionId(), event.partIds()));
    }
}

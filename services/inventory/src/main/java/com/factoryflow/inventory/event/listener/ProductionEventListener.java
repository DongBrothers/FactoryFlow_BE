package com.factoryflow.inventory.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.inventory.config.EventQueueConfig;
import com.factoryflow.inventory.event.listener.payload.MaterialRequested;
import com.factoryflow.inventory.event.listener.payload.ProductionCancelled;
import com.factoryflow.inventory.event.listener.payload.ProductionCompleted;
import com.factoryflow.inventory.service.FinishedVehicleService;
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
    private final FinishedVehicleService finishedVehicleService;
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

    /** 생산 취소: 그 차의 예약을 풀고, 풀린 부품을 기다리던 차에 먼저 온 순서로 넘긴다. 처리 기록과 같은 트랜잭션이다. */
    @RabbitListener(queues = EventQueueConfig.PRODUCTION_CANCELLED_QUEUE)
    public void onProductionCancelled(EventEnvelope envelope) {
        ProductionCancelled event =
                objectMapper.treeToValue(envelope.payload(), ProductionCancelled.class);
        processedEventGuard.runOnce(
                envelope, () -> partReservationService.release(event.productionId()));
    }

    /** 생산 완료: 그 차에 잡아 둔 부품을 사용 처리하고 완성차 재고를 넣는다. 처리 기록과 같은 트랜잭션이다. */
    @RabbitListener(queues = EventQueueConfig.PRODUCTION_COMPLETED_QUEUE)
    public void onProductionCompleted(EventEnvelope envelope) {
        ProductionCompleted event =
                objectMapper.treeToValue(envelope.payload(), ProductionCompleted.class);
        processedEventGuard.runOnce(envelope, () -> finishedVehicleService.stock(event));
    }
}

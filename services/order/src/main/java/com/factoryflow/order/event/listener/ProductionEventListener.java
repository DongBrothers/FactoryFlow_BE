package com.factoryflow.order.event.listener;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.event.ProcessedEventGuard;
import com.factoryflow.order.config.EventQueueConfig;
import com.factoryflow.order.event.listener.payload.ProductionCancelRejected;
import com.factoryflow.order.event.listener.payload.ProductionCancelled;
import com.factoryflow.order.event.listener.payload.ProductionCompleted;
import com.factoryflow.order.event.listener.payload.ProductionStatusChanged;
import com.factoryflow.order.service.OrderVehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * production 의 생산 건 상태 변화를 받는다. 처리 기록과 상태 갱신은 한 트랜잭션이고, 받은 traceId 는 ProcessedEventGuard 가 이어 준다.
 */
@Component
@RequiredArgsConstructor
public class ProductionEventListener {

    private final ProcessedEventGuard processedEventGuard;
    private final OrderVehicleService orderVehicleService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = EventQueueConfig.STATUS_CHANGED_QUEUE)
    public void onStatusChanged(EventEnvelope envelope) {
        ProductionStatusChanged event =
                objectMapper.treeToValue(envelope.payload(), ProductionStatusChanged.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        orderVehicleService.applyProductionStatus(
                                event.orderId(),
                                event.productionId(),
                                event.productId(),
                                event.status()));
    }

    /** 생산 건 취소: 차량 CANCELLED, 답이 다 오면 취소 회차를 끝낸다. 처리 기록과 같은 트랜잭션이다. */
    @RabbitListener(queues = EventQueueConfig.PRODUCTION_CANCELLED_QUEUE)
    public void onCancelled(EventEnvelope envelope) {
        ProductionCancelled event =
                objectMapper.treeToValue(envelope.payload(), ProductionCancelled.class);
        processedEventGuard.runOnce(
                envelope,
                () -> orderVehicleService.applyCancelled(event.orderId(), event.productionId()));
    }

    /** 생산 건 취소 거절: 차량을 거절 때의 상태로 되돌리고, 답이 다 오면 취소 회차를 끝낸다. */
    @RabbitListener(queues = EventQueueConfig.PRODUCTION_CANCEL_REJECTED_QUEUE)
    public void onCancelRejected(EventEnvelope envelope) {
        ProductionCancelRejected event =
                objectMapper.treeToValue(envelope.payload(), ProductionCancelRejected.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        orderVehicleService.applyCancelRejected(
                                event.orderId(), event.productionId(), event.status()));
    }

    /** 생산 완료: 차량 COMPLETED + VIN, 품목·주문 요약. 처리 기록과 같은 트랜잭션이다. */
    @RabbitListener(queues = EventQueueConfig.PRODUCTION_COMPLETED_QUEUE)
    public void onProductionCompleted(EventEnvelope envelope) {
        ProductionCompleted event =
                objectMapper.treeToValue(envelope.payload(), ProductionCompleted.class);
        processedEventGuard.runOnce(
                envelope,
                () ->
                        orderVehicleService.complete(
                                event.orderId(),
                                event.productionId(),
                                event.productId(),
                                event.vin()));
    }
}

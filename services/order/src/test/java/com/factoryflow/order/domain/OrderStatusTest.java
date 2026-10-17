package com.factoryflow.order.domain;

import static com.factoryflow.order.domain.OrderStatus.CANCELLED;
import static com.factoryflow.order.domain.OrderStatus.CANCEL_REQUESTED;
import static com.factoryflow.order.domain.OrderStatus.COMPLETED;
import static com.factoryflow.order.domain.OrderStatus.IN_PRODUCTION;
import static com.factoryflow.order.domain.OrderStatus.RECEIVED;
import static com.factoryflow.order.domain.OrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 상태 요약 규칙: 차량 → 품목, 품목 → 주문 (docs/specs/saga-order-fulfillment.md "요약"). */
class OrderStatusTest {

    @Test
    void allCancelledIsCancelled() {
        assertThat(OrderStatus.summarize(List.of(CANCELLED, CANCELLED))).isEqualTo(CANCELLED);
    }

    @Test
    void anyCancelRequestedIsCancelRequested() {
        assertThat(OrderStatus.summarize(List.of(IN_PRODUCTION, CANCEL_REQUESTED)))
                .isEqualTo(CANCEL_REQUESTED);
        assertThat(OrderStatus.summarize(List.of(RECEIVED, CANCEL_REQUESTED)))
                .isEqualTo(CANCEL_REQUESTED);
    }

    @Test
    void allShippedIsShipped() {
        assertThat(OrderStatus.summarize(List.of(SHIPPED, CANCELLED, SHIPPED))).isEqualTo(SHIPPED);
    }

    @Test
    void allCompletedOrShippedIsCompleted() {
        assertThat(OrderStatus.summarize(List.of(COMPLETED, CANCELLED, COMPLETED)))
                .isEqualTo(COMPLETED);
        assertThat(OrderStatus.summarize(List.of(COMPLETED, SHIPPED))).isEqualTo(COMPLETED);
    }

    @Test
    void anyInProductionCompletedOrShippedIsInProduction() {
        assertThat(OrderStatus.summarize(List.of(IN_PRODUCTION, RECEIVED)))
                .isEqualTo(IN_PRODUCTION);
        assertThat(OrderStatus.summarize(List.of(COMPLETED, RECEIVED))).isEqualTo(IN_PRODUCTION);
        assertThat(OrderStatus.summarize(List.of(SHIPPED, RECEIVED))).isEqualTo(IN_PRODUCTION);
    }

    @Test
    void notStartedVehiclesAreReceived() {
        // 부품을 기다리는 차도 접수다. 시작 안 된 차만 남으면 부품 대기 단계 없이 접수로 요약한다 (#117)
        assertThat(OrderStatus.summarize(List.of(RECEIVED, RECEIVED))).isEqualTo(RECEIVED);
    }

    @Test
    void otherwiseReceived() {
        assertThat(OrderStatus.summarize(List.of(RECEIVED, CANCELLED))).isEqualTo(RECEIVED);
    }
}

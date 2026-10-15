package com.factoryflow.order.domain;

import static com.factoryflow.order.domain.OrderStatus.CANCELLED;
import static com.factoryflow.order.domain.OrderStatus.CANCEL_REQUESTED;
import static com.factoryflow.order.domain.OrderStatus.COMPLETED;
import static com.factoryflow.order.domain.OrderStatus.IN_PRODUCTION;
import static com.factoryflow.order.domain.OrderStatus.RECEIVED;
import static com.factoryflow.order.domain.OrderStatus.WAITING_MATERIAL;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 주문 상태 요약 규칙 (docs/specs/saga-order-fulfillment.md "customer_order.status 요약"). */
class OrderStatusTest {

    @Test
    void allCancelledIsCancelled() {
        assertThat(OrderStatus.summarize(List.of(CANCELLED, CANCELLED))).isEqualTo(CANCELLED);
    }

    @Test
    void anyCancelRequestedIsCancelRequested() {
        assertThat(OrderStatus.summarize(List.of(IN_PRODUCTION, CANCEL_REQUESTED)))
                .isEqualTo(CANCEL_REQUESTED);
    }

    @Test
    void remainingAllCompletedIsCompletedIgnoringCancelled() {
        assertThat(OrderStatus.summarize(List.of(COMPLETED, CANCELLED, COMPLETED)))
                .isEqualTo(COMPLETED);
    }

    @Test
    void anyInProductionOrCompletedIsInProduction() {
        assertThat(OrderStatus.summarize(List.of(IN_PRODUCTION, WAITING_MATERIAL)))
                .isEqualTo(IN_PRODUCTION);
        assertThat(OrderStatus.summarize(List.of(COMPLETED, RECEIVED))).isEqualTo(IN_PRODUCTION);
    }

    @Test
    void anyWaitingMaterialIsWaitingMaterial() {
        assertThat(OrderStatus.summarize(List.of(WAITING_MATERIAL, RECEIVED)))
                .isEqualTo(WAITING_MATERIAL);
    }

    @Test
    void otherwiseReceived() {
        assertThat(OrderStatus.summarize(List.of(RECEIVED, CANCELLED))).isEqualTo(RECEIVED);
    }
}

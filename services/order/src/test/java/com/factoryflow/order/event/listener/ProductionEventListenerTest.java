package com.factoryflow.order.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.exception.OrderErrorCode;
import com.factoryflow.test.container.TestContainersConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** production.status-changed 수신으로 주문 품목·주문 상태 갱신 (docs/specs/order-production-status.md). */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
class ProductionEventListenerTest {

    static final String DLQ = "order.production-status-changed.dlq";
    static final long CAR_A = 1L;
    static final long CAR_B = 2L;

    @Autowired ProductionEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    long orderId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM order_vehicle");
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM customer_order");
        jdbc.update("DELETE FROM processed_event");
        amqpAdmin.purgeQueue(DLQ, false);
        orderId = insertOrder();
        insertItem(orderId, CAR_A, 2);
        insertItem(orderId, CAR_B, 1);
    }

    @Test
    void plannedCreatesReceivedVehicle() {
        listener.onStatusChanged(statusChanged(101L, CAR_A, "PLANNED"));

        assertThat(vehicleStatus(101L)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_vehicle", Integer.class))
                .isEqualTo(1);
        assertThat(itemStatus(CAR_A)).isEqualTo("RECEIVED");
        assertThat(orderStatus()).isEqualTo("RECEIVED");
    }

    @Test
    void oneOfTwoVehiclesReadyMakesItemAndOrderInProduction() {
        listener.onStatusChanged(statusChanged(101L, CAR_A, "PLANNED"));
        listener.onStatusChanged(statusChanged(102L, CAR_A, "PLANNED"));

        listener.onStatusChanged(statusChanged(101L, CAR_A, "READY"));

        assertThat(vehicleStatus(101L)).isEqualTo("IN_PRODUCTION");
        assertThat(vehicleStatus(102L)).isEqualTo("RECEIVED");
        assertThat(itemStatus(CAR_A)).isEqualTo("IN_PRODUCTION");
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");
    }

    @Test
    void notYetKnownVehicleCountsAsReceived() {
        // 품목 A 는 2대인데 한 대만 알려졌다. 모르는 한 대는 RECEIVED 로 보고 요약한다
        // 아는 한 대가 COMPLETED 여도 모르는 한 대가 RECEIVED 라 품목은 COMPLETED 가 아니라 IN_PRODUCTION
        listener.onStatusChanged(statusChanged(101L, CAR_A, "COMPLETED"));

        assertThat(vehicleStatus(101L)).isEqualTo("COMPLETED");
        assertThat(itemStatus(CAR_A)).isEqualTo("IN_PRODUCTION");
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");
    }

    @Test
    void waitingForMaterialShowsAsReceived() {
        // 부품 대기(WAITING_MATERIAL)는 주문 쪽 상태가 아니다. 고객에게는 접수로 보인다 (#117)
        listener.onStatusChanged(statusChanged(101L, CAR_A, "PLANNED"));
        listener.onStatusChanged(statusChanged(101L, CAR_A, "WAITING_MATERIAL"));

        assertThat(vehicleStatus(101L)).isEqualTo("RECEIVED");
        assertThat(itemStatus(CAR_A)).isEqualTo("RECEIVED");
        assertThat(orderStatus()).isEqualTo("RECEIVED");
    }

    @Test
    void rejectedProductMakesCancelledVehiclesAndCancelsTheItem() {
        // 주문 뒤 제품이 사라져 production 이 처음부터 취소된 생산 건으로 알린다 (docs/specs/stalled-flows.md S8)
        listener.onStatusChanged(statusChanged(101L, CAR_A, "PLANNED"));
        listener.onStatusChanged(statusChanged(201L, CAR_B, "CANCELLED"));

        assertThat(vehicleStatus(201L)).isEqualTo("CANCELLED");
        assertThat(itemStatus(CAR_B)).isEqualTo("CANCELLED");
        assertThat(itemStatus(CAR_A)).isEqualTo("RECEIVED");
        assertThat(orderStatus()).isEqualTo("RECEIVED");
    }

    @Test
    void itemsCanDifferAndOrderShowsInProduction() {
        listener.onStatusChanged(statusChanged(101L, CAR_A, "READY"));
        listener.onStatusChanged(statusChanged(201L, CAR_B, "WAITING_MATERIAL"));

        assertThat(itemStatus(CAR_A)).isEqualTo("IN_PRODUCTION");
        assertThat(itemStatus(CAR_B)).isEqualTo("RECEIVED");
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");
    }

    @Test
    void failedIsNoLongerAProductionStatus() {
        // 설비 고장은 생산 실패가 아니라 재배정이다 (docs/specs/equipment-status.md). FAILED 는 모르는 생산 상태로 처리 오류다
        assertThatThrownBy(() -> listener.onStatusChanged(statusChanged(201L, CAR_B, "FAILED")))
                .isInstanceOfSatisfying(
                        CustomException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(OrderErrorCode.UNKNOWN_PRODUCTION_STATUS));
        assertThat(itemStatus(CAR_B)).isEqualTo("RECEIVED");
    }

    @Test
    void finishedVehicleIgnoresStatusChanged() {
        insertVehicle(CAR_A, 101L, "COMPLETED");
        insertVehicle(CAR_A, 102L, "CANCELLED");
        insertVehicle(CAR_B, 201L, "SHIPPED");

        listener.onStatusChanged(statusChanged(101L, CAR_A, "WAITING_MATERIAL"));
        listener.onStatusChanged(statusChanged(102L, CAR_A, "READY"));
        listener.onStatusChanged(statusChanged(201L, CAR_B, "READY"));

        assertThat(vehicleStatus(101L)).isEqualTo("COMPLETED");
        assertThat(vehicleStatus(102L)).isEqualTo("CANCELLED");
        assertThat(vehicleStatus(201L)).isEqualTo("SHIPPED");
    }

    @Test
    void cancelRequestedVehicleAcceptsOnlyCancelled() {
        insertVehicle(CAR_A, 101L, "CANCEL_REQUESTED");
        insertVehicle(CAR_B, 201L, "CANCEL_REQUESTED");
        // 취소 요청 때 품목과 주문도 CANCEL_REQUESTED 가 된다
        jdbc.update("UPDATE order_item SET status = 'CANCEL_REQUESTED'");
        jdbc.update("UPDATE customer_order SET status = 'CANCEL_REQUESTED'");

        listener.onStatusChanged(statusChanged(101L, CAR_A, "READY"));
        listener.onStatusChanged(statusChanged(201L, CAR_B, "CANCELLED"));

        assertThat(vehicleStatus(101L)).isEqualTo("CANCEL_REQUESTED");
        assertThat(vehicleStatus(201L)).isEqualTo("CANCELLED");
        assertThat(itemStatus(CAR_B)).isEqualTo("CANCELLED");
        assertThat(orderStatus()).isEqualTo("CANCEL_REQUESTED");
    }

    @Test
    void moreVehiclesThanItemQtyIsAnError() {
        insertVehicle(CAR_B, 201L, "RECEIVED");

        assertThatThrownBy(() -> listener.onStatusChanged(statusChanged(202L, CAR_B, "PLANNED")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_VEHICLE_OVERFLOW);
    }

    @Test
    void sameEventTwiceIsAppliedOnce() {
        EventEnvelope event = statusChanged(101L, CAR_A, "READY");
        listener.onStatusChanged(event);
        jdbc.update("UPDATE order_vehicle SET status = 'RECEIVED' WHERE production_id = 101");

        listener.onStatusChanged(event);

        assertThat(vehicleStatus(101L)).isEqualTo("RECEIVED");
    }

    @Test
    void receivesStatusChangedFromRabbitMq() {
        send(statusChanged(101L, CAR_A, "READY"));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(itemStatus(CAR_A)).isEqualTo("IN_PRODUCTION"));
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");
    }

    @Test
    void unknownItemIsDeadLettered() {
        EventEnvelope event = statusChanged(901L, 999_999L, "READY");

        send(event);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(DLQ).getMessageCount() == 1);
        Message dead = rabbitTemplate.receive(DLQ);
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        assertThat(orderStatus()).isEqualTo("RECEIVED");
    }

    private EventEnvelope statusChanged(long productionId, long productId, String status) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "production.status-changed",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of(
                                "orderId", orderId,
                                "productionId", productionId,
                                "productId", productId,
                                "status", status)));
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "production.status-changed",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private long insertOrder() {
        String orderNo = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO customer_order (order_no, customer_id, status, created_at, updated_at)"
                        + " VALUES (?, 7, 'RECEIVED', NOW(6), NOW(6))",
                orderNo);
        return jdbc.queryForObject(
                "SELECT id FROM customer_order WHERE order_no = ?", Long.class, orderNo);
    }

    private void insertItem(long orderId, long productId, int qty) {
        jdbc.update(
                "INSERT INTO order_item (order_id, product_id, product_code, product_name, qty,"
                        + " status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'RECEIVED', NOW(6), NOW(6))",
                orderId,
                productId,
                "CAR-" + productId,
                "차 " + productId,
                qty);
    }

    private void insertVehicle(long productId, long productionId, String status) {
        jdbc.update(
                "INSERT INTO order_vehicle (order_id, order_item_id, production_id, status,"
                        + " created_at, updated_at)"
                        + " SELECT order_id, id, ?, ?, NOW(6), NOW(6) FROM order_item"
                        + " WHERE order_id = ? AND product_id = ?",
                productionId,
                status,
                orderId,
                productId);
    }

    private String vehicleStatus(long productionId) {
        return jdbc.queryForObject(
                "SELECT status FROM order_vehicle WHERE production_id = ?",
                String.class,
                productionId);
    }

    private String itemStatus(long productId) {
        return jdbc.queryForObject(
                "SELECT status FROM order_item WHERE order_id = ? AND product_id = ?",
                String.class,
                orderId,
                productId);
    }

    private String orderStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM customer_order WHERE id = ?", String.class, orderId);
    }
}

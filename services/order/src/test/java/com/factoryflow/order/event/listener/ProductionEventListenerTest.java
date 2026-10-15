package com.factoryflow.order.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
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
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM customer_order");
        jdbc.update("DELETE FROM processed_event");
        amqpAdmin.purgeQueue(DLQ, false);
        orderId = insertOrder();
        insertItem(orderId, CAR_A, "RECEIVED");
        insertItem(orderId, CAR_B, "RECEIVED");
    }

    @Test
    void waitingMaterialMovesItemAndOrderToWaitingMaterial() {
        listener.onStatusChanged(statusChanged(CAR_A, "WAITING_MATERIAL"));

        assertThat(itemStatus(CAR_A)).isEqualTo("WAITING_MATERIAL");
        assertThat(itemStatus(CAR_B)).isEqualTo("RECEIVED");
        assertThat(orderStatus()).isEqualTo("WAITING_MATERIAL");
    }

    @Test
    void readyMovesItemAndOrderToInProduction() {
        listener.onStatusChanged(statusChanged(CAR_A, "READY"));

        assertThat(itemStatus(CAR_A)).isEqualTo("IN_PRODUCTION");
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");
    }

    @Test
    void itemsCanDifferAndOrderShowsInProduction() {
        listener.onStatusChanged(statusChanged(CAR_A, "READY"));
        listener.onStatusChanged(statusChanged(CAR_B, "WAITING_MATERIAL"));

        assertThat(itemStatus(CAR_A)).isEqualTo("IN_PRODUCTION");
        assertThat(itemStatus(CAR_B)).isEqualTo("WAITING_MATERIAL");
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");
    }

    @Test
    void finishedItemIgnoresStatusChanged() {
        jdbc.update("UPDATE order_item SET status = 'COMPLETED' WHERE product_id = ?", CAR_A);
        jdbc.update("UPDATE order_item SET status = 'CANCELLED' WHERE product_id = ?", CAR_B);

        listener.onStatusChanged(statusChanged(CAR_A, "WAITING_MATERIAL"));
        listener.onStatusChanged(statusChanged(CAR_B, "READY"));

        assertThat(itemStatus(CAR_A)).isEqualTo("COMPLETED");
        assertThat(itemStatus(CAR_B)).isEqualTo("CANCELLED");
    }

    @Test
    void cancelRequestedItemAcceptsOnlyCancelled() {
        jdbc.update("UPDATE order_item SET status = 'CANCEL_REQUESTED'");

        listener.onStatusChanged(statusChanged(CAR_A, "READY"));
        listener.onStatusChanged(statusChanged(CAR_B, "CANCELLED"));

        assertThat(itemStatus(CAR_A)).isEqualTo("CANCEL_REQUESTED");
        assertThat(itemStatus(CAR_B)).isEqualTo("CANCELLED");
        assertThat(orderStatus()).isEqualTo("CANCEL_REQUESTED");
    }

    @Test
    void sameEventTwiceIsAppliedOnce() {
        EventEnvelope event = statusChanged(CAR_A, "READY");
        listener.onStatusChanged(event);
        jdbc.update("UPDATE order_item SET status = 'RECEIVED' WHERE product_id = ?", CAR_A);

        listener.onStatusChanged(event);

        assertThat(itemStatus(CAR_A)).isEqualTo("RECEIVED");
    }

    @Test
    void receivesStatusChangedFromRabbitMq() {
        send(statusChanged(CAR_A, "READY"));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(itemStatus(CAR_A)).isEqualTo("IN_PRODUCTION"));
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");
    }

    @Test
    void unknownItemIsDeadLettered() {
        EventEnvelope event = statusChanged(999_999L, "READY");

        send(event);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(DLQ).getMessageCount() == 1);
        Message dead = rabbitTemplate.receive(DLQ);
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        assertThat(orderStatus()).isEqualTo("RECEIVED");
    }

    private EventEnvelope statusChanged(long productId, String status) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "production.status-changed",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of(
                                "orderId", orderId,
                                "productionId", 100L + productId,
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

    private void insertItem(long orderId, long productId, String status) {
        jdbc.update(
                "INSERT INTO order_item (order_id, product_id, product_code, product_name, qty,"
                        + " status, created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, NOW(6), NOW(6))",
                orderId,
                productId,
                "CAR-" + productId,
                "차 " + productId,
                status);
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

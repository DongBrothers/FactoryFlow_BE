package com.factoryflow.order.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.test.container.TestContainersConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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

/** shipment.completed 수신으로 차량 SHIPPED, 품목·주문 요약 (docs/specs/shipment.md). */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
class ShipmentCompletedTest {

    static final String DLQ = "order.shipment-completed.dlq";
    static final long CAR_A = 1L;
    static final long CAR_B = 2L;
    static final long CAR_C = 3L;

    @Autowired InventoryEventListener listener;
    @Autowired ProductionEventListener productionListener;
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
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        amqpAdmin.purgeQueue(DLQ, false);
        // 주문: A 2대 + B 1대, 세 대 모두 생산 완료
        orderId = insertOrder("COMPLETED");
        insertItem(CAR_A, 2, "COMPLETED");
        insertItem(CAR_B, 1, "COMPLETED");
        insertVehicle(CAR_A, 101L, "COMPLETED", vin(101L));
        insertVehicle(CAR_A, 102L, "COMPLETED", vin(102L));
        insertVehicle(CAR_B, 201L, "COMPLETED", vin(201L));
    }

    @Test
    void shippedVehicleIsShippedAndOrderIsShippedWhenEveryVehicleIsShipped() {
        listener.onShipmentCompleted(shipped(101L, CAR_A));

        assertThat(vehicle(101L)).containsEntry("status", "SHIPPED");
        assertThat(itemStatus(CAR_A)).isEqualTo("COMPLETED");
        assertThat(orderStatus()).isEqualTo("COMPLETED");

        listener.onShipmentCompleted(shipped(102L, CAR_A));
        assertThat(itemStatus(CAR_A)).isEqualTo("SHIPPED");
        assertThat(orderStatus()).isEqualTo("COMPLETED");

        listener.onShipmentCompleted(shipped(201L, CAR_B));
        assertThat(itemStatus(CAR_B)).isEqualTo("SHIPPED");
        assertThat(orderStatus()).isEqualTo("SHIPPED");
    }

    @Test
    void vehicleNotYetCompletedIsShippedWithVinAndALateCompletedChangesNothing() {
        // 큐가 달라 출고가 production.completed 보다 먼저 처리될 수 있다. 출고됐다는 것은 생산이 끝났다는 뜻이다
        jdbc.update(
                "UPDATE order_vehicle SET status = 'IN_PRODUCTION', vin = NULL WHERE production_id = 101");

        listener.onShipmentCompleted(shipped(101L, CAR_A));
        productionListener.onProductionCompleted(completed(101L, CAR_A));

        assertThat(vehicle(101L))
                .containsEntry("status", "SHIPPED")
                .containsEntry("vin", vin(101L));
    }

    @Test
    void cancelRequestedVehicleIsShippedAndALateRejectionChangesNothing() {
        // order 가 늦은 상태로 취소를 받았어도 완성된 차는 production 이 반드시 거절한다
        jdbc.update("UPDATE customer_order SET cancel_requested_at = NOW(6) WHERE id = ?", orderId);
        jdbc.update("UPDATE order_vehicle SET status = 'CANCEL_REQUESTED'");

        listener.onShipmentCompleted(shipped(101L, CAR_A));
        assertThat(vehicle(101L)).containsEntry("status", "SHIPPED");

        productionListener.onCancelRejected(cancelRejected(101L));
        assertThat(vehicle(101L)).containsEntry("status", "SHIPPED");
    }

    @Test
    void cancelledVehicleIsNotChanged() {
        jdbc.update(
                "UPDATE order_vehicle SET status = 'CANCELLED', vin = NULL WHERE production_id = 201");

        listener.onShipmentCompleted(shipped(201L, CAR_B));

        assertThat(vehicle(201L)).containsEntry("status", "CANCELLED").containsEntry("vin", null);
    }

    @Test
    void sameShipmentTwiceIsAppliedOnce() {
        EventEnvelope event = shipped(101L, CAR_A);

        listener.onShipmentCompleted(event);
        listener.onShipmentCompleted(event);
        listener.onShipmentCompleted(shipped(101L, CAR_A));

        assertThat(vehicle(101L)).containsEntry("status", "SHIPPED");
        assertThat(itemStatus(CAR_A)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void notYetKnownVehicleIsAddedAndShipped() {
        insertItem(CAR_C, 1, "IN_PRODUCTION");

        listener.onShipmentCompleted(shipped(301L, CAR_C));

        assertThat(vehicle(301L))
                .containsEntry("status", "SHIPPED")
                .containsEntry("vin", vin(301L));
        assertThat(itemStatus(CAR_C)).isEqualTo("SHIPPED");
    }

    @Test
    void receivesShipmentFromRabbitMq() {
        send(shipped(201L, CAR_B));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(itemStatus(CAR_B)).isEqualTo("SHIPPED"));
    }

    @Test
    void unknownItemIsDeadLettered() {
        EventEnvelope event = shipped(901L, 99L);

        send(event);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(DLQ).getMessageCount() == 1);
        Message dead = rabbitTemplate.receive(DLQ);
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_vehicle WHERE production_id = 901",
                                Integer.class))
                .isZero();
    }

    private static String vin(long productionId) {
        return String.format("KMH000017TU%06d", productionId);
    }

    private EventEnvelope shipped(long productionId, long productId) {
        return envelope(
                "shipment.completed",
                Map.of(
                        "orderId", orderId,
                        "productionId", productionId,
                        "productId", productId,
                        "vin", vin(productionId)));
    }

    private EventEnvelope completed(long productionId, long productId) {
        return envelope(
                "production.completed",
                Map.of(
                        "orderId", orderId,
                        "productionId", productionId,
                        "productId", productId,
                        "vin", vin(productionId)));
    }

    private EventEnvelope cancelRejected(long productionId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("productionId", productionId);
        payload.put("reason", "ALREADY_STARTED");
        payload.put("status", "COMPLETED");
        return envelope("production.cancel-rejected", payload);
    }

    private EventEnvelope envelope(String name, Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID(),
                name,
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(payload));
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "shipment.completed",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private long insertOrder(String status) {
        String orderNo = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO customer_order (order_no, customer_id, status, created_at, updated_at)"
                        + " VALUES (?, 7, ?, NOW(6), NOW(6))",
                orderNo,
                status);
        return jdbc.queryForObject(
                "SELECT id FROM customer_order WHERE order_no = ?", Long.class, orderNo);
    }

    private void insertItem(long productId, int qty, String status) {
        jdbc.update(
                "INSERT INTO order_item (order_id, product_id, product_code, product_name, qty,"
                        + " status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                orderId,
                productId,
                "CAR-" + productId,
                "차 " + productId,
                qty,
                status);
    }

    private void insertVehicle(long productId, long productionId, String status, String vin) {
        jdbc.update(
                "INSERT INTO order_vehicle (order_id, order_item_id, production_id, status, vin,"
                        + " created_at, updated_at)"
                        + " SELECT order_id, id, ?, ?, ?, NOW(6), NOW(6) FROM order_item"
                        + " WHERE order_id = ? AND product_id = ?",
                productionId,
                status,
                vin,
                orderId,
                productId);
    }

    private Map<String, Object> vehicle(long productionId) {
        return jdbc.queryForMap(
                "SELECT status, vin FROM order_vehicle WHERE production_id = ?", productionId);
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

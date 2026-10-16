package com.factoryflow.order.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.order.repository.OrderItemRepository;
import com.factoryflow.order.service.OrderVehicleService;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** production.completed 수신으로 차량 COMPLETED + VIN, 품목·주문 요약 (docs/specs/production-completion.md). */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
class ProductionCompletedTest {

    static final String DLQ = "order.production-completed.dlq";
    static final long CAR_A = 1L;
    static final long CAR_B = 2L;
    static final long CAR_C = 3L;

    @Autowired ProductionEventListener listener;
    @Autowired OrderVehicleService orderVehicleService;
    @Autowired OrderItemRepository orderItemRepository;
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
        // 주문: A 2대 + B 1대, 세 대 모두 생산 중
        orderId = insertOrder("IN_PRODUCTION");
        insertItem(CAR_A, 2, "IN_PRODUCTION");
        insertItem(CAR_B, 1, "IN_PRODUCTION");
        insertVehicle(CAR_A, 101L, "IN_PRODUCTION");
        insertVehicle(CAR_A, 102L, "IN_PRODUCTION");
        insertVehicle(CAR_B, 201L, "IN_PRODUCTION");
    }

    @Test
    void completedVehicleGetsVinAndOrderCompletesWhenEveryVehicleIsDone() {
        listener.onProductionCompleted(completed(101L, CAR_A, "KMH000017TU000101"));

        assertThat(vehicle(101L))
                .containsEntry("status", "COMPLETED")
                .containsEntry("vin", "KMH000017TU000101");
        assertThat(itemStatus(CAR_A)).isEqualTo("IN_PRODUCTION");
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");

        listener.onProductionCompleted(completed(102L, CAR_A, "KMH000017TU000102"));
        assertThat(itemStatus(CAR_A)).isEqualTo("COMPLETED");
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");

        listener.onProductionCompleted(completed(201L, CAR_B, "KMH000027TU000201"));
        assertThat(itemStatus(CAR_B)).isEqualTo("COMPLETED");
        assertThat(orderStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void finishedVehicleIsNotChanged() {
        jdbc.update("UPDATE order_vehicle SET status = 'CANCELLED' WHERE production_id = 201");

        listener.onProductionCompleted(completed(201L, CAR_B, "KMH000027TU000201"));

        assertThat(vehicle(201L)).containsEntry("status", "CANCELLED").containsEntry("vin", null);
    }

    @Test
    void sameEventTwiceIsAppliedOnce() {
        EventEnvelope event = completed(101L, CAR_A, "KMH000017TU000101");

        listener.onProductionCompleted(event);
        listener.onProductionCompleted(event);
        listener.onProductionCompleted(completed(101L, CAR_A, "KMH000017TU000101"));

        assertThat(vehicle(101L))
                .containsEntry("status", "COMPLETED")
                .containsEntry("vin", "KMH000017TU000101");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void receivesCompletedFromRabbitMq() {
        send(completed(201L, CAR_B, "KMH000027TU000201"));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(itemStatus(CAR_B)).isEqualTo("COMPLETED"));
    }

    @Test
    void notYetKnownVehicleIsAddedAndCompleted() {
        // 큐가 달라 completed 가 차량을 만드는 status-changed(PLANNED) 보다 먼저 처리될 수 있다. status-changed 처럼 차량을
        // 만든다
        insertItem(CAR_C, 1, "IN_PRODUCTION");

        listener.onProductionCompleted(completed(301L, CAR_C, "KMH000037TU000301"));

        assertThat(vehicle(301L))
                .containsEntry("status", "COMPLETED")
                .containsEntry("vin", "KMH000037TU000301");
        assertThat(itemStatus(CAR_C)).isEqualTo("COMPLETED");
    }

    @Test
    void unknownItemIsDeadLettered() {
        EventEnvelope event = completed(901L, 99L, "KMH000997TU000901");

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

    @Test
    @Transactional
    void itemChangedByAnotherTransactionMakesTheLaterSummaryFail() {
        // B 의 유일한 차량이 완료되면 품목 B 가 바뀐다. 이 트랜잭션이 품목을 읽은 뒤 다른 처리(status-changed 큐)가 같은 품목을
        // 먼저 바꿨다고 보고(version +1), 이 트랜잭션의 요약 저장은 version 충돌로 실패해야 한다 (리스너는 재시도로 다시 요약한다)
        orderItemRepository.findByOrderIdAndProductId(orderId, CAR_B);
        jdbc.update(
                "UPDATE order_item SET version = version + 1 WHERE order_id = ? AND product_id = ?",
                orderId,
                CAR_B);
        // 충돌은 요약 중 조회가 일으키는 자동 flush 나 마지막 flush 에서 난다
        assertThatThrownBy(
                        () -> {
                            orderVehicleService.complete(orderId, 201L, CAR_B, "KMH000027TU000201");
                            orderItemRepository.flush();
                        })
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private EventEnvelope completed(long productionId, long productId, String vin) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "production.completed",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of(
                                "orderId", orderId,
                                "productionId", productionId,
                                "productId", productId,
                                "vin", vin)));
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "production.completed",
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

package com.factoryflow.production.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

/** inventory 예약 결과 수신으로 생산 건 상태 변경 (docs/specs/production-reservation-result.md). */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
class InventoryEventListenerTest {

    static final String RESERVED_DLQ = "production.inventory-reserved.dlq";

    @Autowired InventoryEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        amqpAdmin.purgeQueue(RESERVED_DLQ, false);
    }

    @Test
    void reservedMakesPlannedProductionReady() {
        long production = insertProduction(42L, 1L, "PLANNED");

        listener.onReserved(reserved(production));

        assertThat(statusOf(production)).isEqualTo("READY");
        String changed = onlyOutbox("production.status-changed");
        assertThat(number(changed, "$.payload.orderId")).isEqualTo(42L);
        assertThat(number(changed, "$.payload.productionId")).isEqualTo(production);
        assertThat(number(changed, "$.payload.productId")).isEqualTo(1L);
        assertThat(JsonPath.<String>read(changed, "$.payload.status")).isEqualTo("READY");
    }

    @Test
    void reservedWaitsForTheWorkOrderToBeReleased() {
        // 예약 완료로 READY 가 되지만 작업지시는 생산 담당이 발행한다 (docs/specs/work-order-release.md). 발행은
        // WorkOrderReleaseTest, 작업지시·공정 5개는 mes 가 확인한다
        long production = insertProduction(42L, 1L, "PLANNED");

        listener.onReserved(reserved(production));

        assertThat(statusOf(production)).isEqualTo("READY");
        assertNotReleased(production);
    }

    @Test
    void reservedTwiceChangesTheStatusOnce() {
        long production = insertProduction(42L, 1L, "PLANNED");

        listener.onReserved(reserved(production));
        listener.onReserved(reserved(production));

        assertThat(statusOf(production)).isEqualTo("READY");
        assertThat(outbox("production.status-changed")).hasSize(1);
    }

    @Test
    void reservationPendingDoesNotReleaseWorkOrder() {
        long production = insertProduction(42L, 1L, "PLANNED");

        listener.onReservationPending(reservationPending(production));

        assertNotReleased(production);
    }

    @Test
    void reservedMakesWaitingProductionReadyAfterReceipt() {
        long production = insertProduction(42L, 1L, "WAITING_MATERIAL");

        listener.onReserved(reserved(production));

        assertThat(statusOf(production)).isEqualTo("READY");
        assertThat(
                        JsonPath.<String>read(
                                onlyOutbox("production.status-changed"), "$.payload.status"))
                .isEqualTo("READY");
    }

    @Test
    void reservationPendingMakesPlannedProductionWaitForMaterial() {
        long production = insertProduction(42L, 1L, "PLANNED");

        listener.onReservationPending(reservationPending(production));

        assertThat(statusOf(production)).isEqualTo("WAITING_MATERIAL");
        assertThat(
                        JsonPath.<String>read(
                                onlyOutbox("production.status-changed"), "$.payload.status"))
                .isEqualTo("WAITING_MATERIAL");
    }

    @Test
    void reservedForCancelledProductionSendsCancelledAgain() {
        long production = insertProduction(42L, 1L, "CANCELLED");

        listener.onReserved(reserved(production));

        assertThat(statusOf(production)).isEqualTo("CANCELLED");
        String cancelled = onlyOutbox("production.cancelled");
        assertThat(number(cancelled, "$.payload.orderId")).isEqualTo(42L);
        assertThat(number(cancelled, "$.payload.productionId")).isEqualTo(production);
        assertThat(outbox("production.status-changed")).isEmpty();
    }

    @Test
    void eventFromNotAllowedStateChangesNothing() {
        long ready = insertProduction(42L, 1L, "READY");
        long waiting = insertProduction(42L, 2L, "WAITING_MATERIAL");

        listener.onReservationPending(reservationPending(ready));
        listener.onReservationPending(reservationPending(waiting));
        listener.onReserved(reserved(ready));

        assertThat(statusOf(ready)).isEqualTo("READY");
        assertThat(statusOf(waiting)).isEqualTo("WAITING_MATERIAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void sameEventTwiceSendsStatusChangedOnce() {
        long production = insertProduction(42L, 1L, "PLANNED");
        EventEnvelope event = reserved(production);

        listener.onReserved(event);
        listener.onReserved(event);

        assertThat(outbox("production.status-changed")).hasSize(1);
    }

    @Test
    void publishedEventKeepsTheReceivedTraceId() {
        long production = insertProduction(42L, 1L, "PLANNED");
        EventEnvelope event = reserved(production);

        listener.onReserved(event);

        assertThat(JsonPath.<String>read(onlyOutbox("production.status-changed"), "$.traceId"))
                .isEqualTo(event.traceId());
    }

    @Test
    void receivesBothResultsFromRabbitMq() {
        long ready = insertProduction(42L, 1L, "PLANNED");
        long waiting = insertProduction(42L, 2L, "PLANNED");

        send("inventory.reserved", reserved(ready));
        send("inventory.reservation-pending", reservationPending(waiting));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(statusOf(ready)).isEqualTo("READY");
                            assertThat(statusOf(waiting)).isEqualTo("WAITING_MATERIAL");
                        });
    }

    @Test
    void unknownProductionIsDeadLettered() {
        EventEnvelope event = reserved(999_999L);

        send("inventory.reserved", event);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(RESERVED_DLQ).getMessageCount() == 1);
        Message dead = rabbitTemplate.receive(RESERVED_DLQ);
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    private EventEnvelope reserved(long productionId) {
        return envelope("inventory.reserved", Map.of("productionId", productionId));
    }

    private EventEnvelope reservationPending(long productionId) {
        return envelope(
                "inventory.reservation-pending",
                Map.of(
                        "productionId",
                        productionId,
                        "shortages",
                        List.of(Map.of("partId", 1011L, "shortQty", 5))));
    }

    private EventEnvelope envelope(String eventName, Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID(),
                eventName,
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(payload));
    }

    private void send(String routingKey, EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                routingKey,
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private long insertProduction(long orderId, long productId, String status) {
        jdbc.update(
                "INSERT INTO production (order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (?, ?, 1, ?, NOW(6), NOW(6))",
                orderId,
                productId,
                status);
        return jdbc.queryForObject(
                "SELECT id FROM production WHERE order_id = ? AND product_id = ?",
                Long.class,
                orderId,
                productId);
    }

    private String statusOf(long productionId) {
        return jdbc.queryForObject(
                "SELECT status FROM production WHERE id = ?", String.class, productionId);
    }

    private List<String> outbox(String eventName) {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = ? ORDER BY id",
                String.class,
                eventName);
    }

    private String onlyOutbox(String eventName) {
        List<String> payloads = outbox(eventName);
        assertThat(payloads).hasSize(1);
        return payloads.get(0);
    }

    private static long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    /** 작업지시를 발행하지 않았다: 발행 시각이 없고 mes 에 넘기지 않았다. */
    private void assertNotReleased(long production) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT released_at IS NULL FROM production WHERE id = ?",
                                Boolean.class,
                                production))
                .isTrue();
        assertThat(outbox("production.released")).isEmpty();
    }
}

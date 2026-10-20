package com.factoryflow.production.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.production.service.ProductionService;
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
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** mes 가 알려 주는 생산 시작, 완성, 발행한 차의 취소·거절 수신 (docs/specs/mes-split.md "2~4"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class MesEventListenerTest {

    static final long ORDER = 42L;
    static final long PRODUCT = 1L;
    static final String VIN = "KMH000017TU000012";

    @Autowired MesEventListener listener;
    @Autowired ProductionService productionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
    }

    @Test
    void productionStartedMovesReadyToInProgressOnce() {
        long production = releasedProduction(1);
        EventEnvelope started = event("mes.production-started", Map.of("productionId", production));

        listener.onProductionStarted(started);
        listener.onProductionStarted(started);
        listener.onProductionStarted(
                event("mes.production-started", Map.of("productionId", production)));

        assertThat(statusOf(production)).isEqualTo("IN_PROGRESS");
        assertThat(outbox("production.status-changed"))
                .extracting(p -> JsonPath.<String>read(p, "$.payload.status"))
                .containsExactly("IN_PROGRESS");
    }

    @Test
    void vehicleCompletedCompletesTheProductionAndTellsInventoryAndOrder() {
        long production = releasedProduction(1);
        listener.onProductionStarted(
                event("mes.production-started", Map.of("productionId", production)));

        listener.onVehicleCompleted(vehicleCompleted(production));

        assertThat(statusOf(production)).isEqualTo("COMPLETED");
        String completed = outbox("production.completed").get(0);
        assertThat(JsonPath.<Integer>read(completed, "$.payload.orderId")).isEqualTo((int) ORDER);
        assertThat(number(completed, "$.payload.productionId")).isEqualTo(production);
        assertThat(JsonPath.<Integer>read(completed, "$.payload.productId"))
                .isEqualTo((int) PRODUCT);
        assertThat(JsonPath.<String>read(completed, "$.payload.vin")).isEqualTo(VIN);
        assertThat(outbox("production.completed")).hasSize(1);
    }

    @Test
    void completionArrivingBeforeTheStartStillCompletesAndTheLateStartIsIgnored() {
        // 큐가 달라 완성 알림이 시작 알림보다 먼저 올 수 있다
        long production = releasedProduction(1);

        listener.onVehicleCompleted(vehicleCompleted(production));
        listener.onProductionStarted(
                event("mes.production-started", Map.of("productionId", production)));

        assertThat(statusOf(production)).isEqualTo("COMPLETED");
        assertThat(outbox("production.completed")).hasSize(1);
        assertThat(outbox("production.status-changed")).isEmpty();
    }

    @Test
    void completionOfAProductionThatIsNoLongerOpenIsIgnored() {
        long production = releasedProduction(1);
        jdbc.update("UPDATE production SET status = 'CANCELLED' WHERE id = ?", production);

        listener.onVehicleCompleted(vehicleCompleted(production));

        assertThat(statusOf(production)).isEqualTo("CANCELLED");
        assertThat(outbox("production.completed")).isEmpty();
    }

    @Test
    void workOrderCancelledCancelsTheReleasedCarAndStartsTheCompensation() {
        long production = releasedProduction(1);

        listener.onWorkOrderCancelled(
                event("mes.work-order-cancelled", Map.of("productionId", production)));
        // eventId 만 다른 재전송: 이미 취소됐으니 cancelled 를 다시 보낸다 (받는 쪽은 여러 번 받아도 안전)
        listener.onWorkOrderCancelled(
                event("mes.work-order-cancelled", Map.of("productionId", production)));

        assertThat(statusOf(production)).isEqualTo("CANCELLED");
        assertThat(outbox("production.cancelled"))
                .hasSize(2)
                .allSatisfy(
                        p -> {
                            assertThat(number(p, "$.payload.productionId")).isEqualTo(production);
                            assertThat(number(p, "$.payload.orderId")).isEqualTo(ORDER);
                        });
    }

    @Test
    void cancelRejectedTellsOrderTheStartedStatusEvenBeforeTheStartArrives() {
        long production = releasedProduction(1);

        listener.onWorkOrderCancelRejected(
                event(
                        "mes.work-order-cancel-rejected",
                        Map.of("productionId", production, "status", "IN_PROGRESS")));

        assertThat(statusOf(production)).isEqualTo("READY");
        String rejected = outbox("production.cancel-rejected").get(0);
        assertThat(number(rejected, "$.payload.productionId")).isEqualTo(production);
        assertThat(JsonPath.<String>read(rejected, "$.payload.reason"))
                .isEqualTo("ALREADY_STARTED");
        assertThat(JsonPath.<String>read(rejected, "$.payload.status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void receivesVehicleCompletedFromRabbitMq() {
        long production = releasedProduction(1);

        send(vehicleCompleted(production));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(production)).isEqualTo("COMPLETED"));
    }

    /** 부품이 다 잡히고 생산 담당이 작업지시를 발행한 차. */
    private long releasedProduction(int seq) {
        jdbc.update(
                "INSERT INTO production (order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'PLANNED', NOW(6), NOW(6))",
                ORDER,
                PRODUCT,
                seq);
        long production =
                jdbc.queryForObject(
                        "SELECT id FROM production WHERE order_id = ? AND seq = ?",
                        Long.class,
                        ORDER,
                        seq);
        productionService.markReserved(production);
        productionService.release(production);
        jdbc.update("DELETE FROM outbox");
        return production;
    }

    private EventEnvelope vehicleCompleted(long production) {
        return event(
                "mes.vehicle-completed",
                Map.of("productionId", production, "workOrderId", 12L, "vin", VIN));
    }

    private EventEnvelope event(String name, Map<String, Object> payload) {
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
                event.eventName(),
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private String statusOf(long production) {
        return jdbc.queryForObject(
                "SELECT status FROM production WHERE id = ?", String.class, production);
    }

    private List<String> outbox(String eventName) {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = ? ORDER BY id",
                String.class,
                eventName);
    }

    private static long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}

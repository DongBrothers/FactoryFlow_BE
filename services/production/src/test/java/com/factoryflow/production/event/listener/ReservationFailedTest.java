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
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** BOM 부품이 inventory 에 없어 예약할 수 없는 차를 취소한다 (docs/specs/stalled-flows.md S18). */
@SpringBootTest
@Import(TestContainersConfig.class)
class ReservationFailedTest {

    @Autowired InventoryEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
    }

    @Test
    void plannedProductionIsCancelledAndAnnounced() {
        long production = insertProduction(42L, "PLANNED");

        listener.onReservationFailed(failed(production));

        assertThat(statusOf(production)).isEqualTo("CANCELLED");
        assertThat(outbox("production.cancelled"))
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(number(c, "$.payload.orderId")).isEqualTo(42L);
                            assertThat(number(c, "$.payload.productionId")).isEqualTo(production);
                        });
    }

    @Test
    void cancelledOrOtherStatesStayAsTheyAre() {
        long cancelled = insertProduction(42L, "CANCELLED");
        long inProgress = insertProduction(43L, "IN_PROGRESS");

        listener.onReservationFailed(failed(cancelled));
        listener.onReservationFailed(failed(inProgress));

        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
        assertThat(statusOf(inProgress)).isEqualTo("IN_PROGRESS");
        assertThat(outbox("production.cancelled")).isEmpty();
    }

    @Test
    void sameFailureTwiceCancelsOnce() {
        long production = insertProduction(42L, "PLANNED");
        EventEnvelope event = failed(production);

        listener.onReservationFailed(event);
        listener.onReservationFailed(event);
        listener.onReservationFailed(failed(production));

        assertThat(outbox("production.cancelled")).hasSize(1);
    }

    @Test
    void receivesFailureFromRabbitMq() {
        long production = insertProduction(42L, "PLANNED");

        send(failed(production));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(production)).isEqualTo("CANCELLED"));
    }

    private EventEnvelope failed(long productionId) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "inventory.reservation-failed",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of(
                                "productionId",
                                productionId,
                                "reason",
                                "PART_NOT_FOUND",
                                "partIds",
                                List.of(999_999L))));
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "inventory.reservation-failed",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private long insertProduction(long orderId, String status) {
        jdbc.update(
                "INSERT INTO production (order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (?, 1, 1, ?, NOW(6), NOW(6))",
                orderId,
                status);
        return jdbc.queryForObject(
                "SELECT id FROM production WHERE order_id = ?", Long.class, orderId);
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

    private static long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}

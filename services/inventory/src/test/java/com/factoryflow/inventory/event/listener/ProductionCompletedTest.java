package com.factoryflow.inventory.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.test.container.TestContainersConfig;
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

/** production.completed 수신으로 부품 사용 처리와 완성차 재고 (docs/specs/production-completion.md). */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
class ProductionCompletedTest {

    static final String DLQ = "inventory.production-completed.dlq";
    static final String VIN = "KMH000017TU000012";

    @Autowired ProductionEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    long engine;
    long tire;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM finished_vehicle");
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM part_reservation");
        jdbc.update("DELETE FROM part");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        amqpAdmin.purgeQueue(DLQ, false);
        // 생산 건 100 이 엔진 1, 타이어 4 를 잡아 두었고, 101 은 타이어 4 를 잡아 두었다
        engine = insertPart("ENG-T", 9, 1);
        tire = insertPart("TIRE-T", 2, 8);
        insertReservation(100L, engine, 1, "RESERVED");
        insertReservation(100L, tire, 4, "RESERVED");
        insertReservation(101L, tire, 4, "RESERVED");
    }

    @Test
    void completedConsumesReservedPartsAndStocksTheFinishedVehicle() {
        listener.onProductionCompleted(completed(UUID.randomUUID(), 100L, VIN));

        assertThat(reservations())
                .extracting(
                        r -> r.get("production_id"), r -> r.get("part_id"), r -> r.get("status"))
                .containsExactlyInAnyOrder(
                        tuple(100L, engine, "CONSUMED"),
                        tuple(100L, tire, "CONSUMED"),
                        tuple(101L, tire, "RESERVED"));
        // 잡아 둔 수량만 줄고, 바로 쓸 수 있는 수량은 그대로다
        assertThat(stock(engine)).containsEntry("available", 9).containsEntry("reserved", 0);
        assertThat(stock(tire)).containsEntry("available", 2).containsEntry("reserved", 4);
        assertThat(
                        jdbc.queryForList(
                                "SELECT part_id, qty, type, ref_type, ref_id FROM stock_history"))
                .extracting(
                        r -> r.get("part_id"),
                        r -> r.get("qty"),
                        r -> r.get("type"),
                        r -> r.get("ref_type"),
                        r -> r.get("ref_id"))
                .containsExactlyInAnyOrder(
                        tuple(engine, 1, "CONSUME", "PRODUCTION", 100L),
                        tuple(tire, 4, "CONSUME", "PRODUCTION", 100L));
        assertThat(jdbc.queryForList("SELECT * FROM finished_vehicle"))
                .singleElement()
                .satisfies(
                        v ->
                                assertThat(v)
                                        .containsEntry("vin", VIN)
                                        .containsEntry("production_id", 100L)
                                        .containsEntry("order_id", 42L)
                                        .containsEntry("product_id", 1L)
                                        .containsEntry("status", "IN_STOCK"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void sameEventTwiceAppliesOnce() {
        EventEnvelope event = completed(UUID.randomUUID(), 100L, VIN);

        listener.onProductionCompleted(event);
        listener.onProductionCompleted(event);

        assertAppliedOnce();
    }

    @Test
    void sameProductionWithAnotherEventIdAppliesOnce() {
        listener.onProductionCompleted(completed(UUID.randomUUID(), 100L, VIN));
        listener.onProductionCompleted(completed(UUID.randomUUID(), 100L, VIN));

        assertAppliedOnce();
    }

    @Test
    void receivesCompletedFromRabbitMq() {
        send(completed(UUID.randomUUID(), 100L, VIN));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM finished_vehicle",
                                                        Integer.class))
                                        .isEqualTo(1));
    }

    @Test
    void productionWithoutReservedPartsIsDeadLettered() {
        EventEnvelope event = completed(UUID.randomUUID(), 999L, VIN);

        send(event);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(DLQ).getMessageCount() == 1);
        Message dead = rabbitTemplate.receive(DLQ);
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finished_vehicle", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class))
                .isZero();
    }

    @Test
    void deletedPartIsPartNotFoundAndChangesNothing() {
        // 잡아 둔 부품이 그사이 삭제됐으면 원인을 알 수 있게 PART_NOT_FOUND 로 실패한다 (재시도 후 DLQ)
        jdbc.update("UPDATE part SET is_deleted = TRUE WHERE id = ?", tire);

        assertThatThrownBy(
                        () ->
                                listener.onProductionCompleted(
                                        completed(UUID.randomUUID(), 100L, VIN)))
                .isInstanceOfSatisfying(
                        CustomException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(InventoryErrorCode.PART_NOT_FOUND));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finished_vehicle", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT status FROM part_reservation WHERE production_id = 100",
                                String.class))
                .containsExactly("RESERVED");
        assertThat(stock(engine)).containsEntry("reserved", 1);
    }

    private void assertAppliedOnce() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finished_vehicle", Integer.class))
                .isEqualTo(1);
        assertThat(stock(tire)).containsEntry("reserved", 4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stock_history WHERE type = 'CONSUME'",
                                Integer.class))
                .isEqualTo(2);
    }

    private EventEnvelope completed(UUID eventId, long productionId, String vin) {
        return new EventEnvelope(
                eventId,
                "production.completed",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of(
                                "orderId",
                                42L,
                                "productionId",
                                productionId,
                                "productId",
                                1L,
                                "vin",
                                vin)));
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

    private List<Map<String, Object>> reservations() {
        return jdbc.queryForList("SELECT production_id, part_id, status FROM part_reservation");
    }

    private Map<String, Object> stock(long partId) {
        return jdbc.queryForMap("SELECT available, reserved FROM part WHERE id = ?", partId);
    }

    private long insertPart(String code, int available, int reserved) {
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, reserved, created_at, updated_at)"
                        + " VALUES (?, ?, 'EA', ?, ?, NOW(6), NOW(6))",
                code,
                code,
                available,
                reserved);
        return jdbc.queryForObject("SELECT id FROM part WHERE code = ?", Long.class, code);
    }

    private void insertReservation(long productionId, long partId, int qty, String status) {
        jdbc.update(
                "INSERT INTO part_reservation (production_id, part_id, qty, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                productionId,
                partId,
                qty,
                status);
    }
}

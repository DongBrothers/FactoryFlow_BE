package com.factoryflow.inventory.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
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

/** production.cancelled 수신으로 예약 해제와 풀린 부품의 재배정 (docs/specs/order-cancel.md "3. 예약 해제"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class ProductionCancelledTest {

    @Autowired ProductionEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;

    long engine;
    long tire;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM part_reservation");
        jdbc.update("DELETE FROM part");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        // 생산 건 100 은 엔진 1 을 잡고 타이어 4 를 기다린다. 101 은 엔진 1 을 기다린다 (재고 0)
        engine = insertPart("ENG-T", 0, 1);
        tire = insertPart("TIRE-T", 2, 0);
        insertReservation(100L, engine, 1, "RESERVED");
        insertReservation(100L, tire, 4, "WAITING");
        insertReservation(101L, engine, 1, "WAITING");
    }

    @Test
    void cancelledProductionReleasesItsLinesAndTheFreedPartGoesToTheNextWaitingCar() {
        listener.onProductionCancelled(cancelled(UUID.randomUUID(), 100L));

        assertThat(lines())
                .containsExactly(
                        tuple(100L, engine, "RELEASED"),
                        tuple(100L, tire, "RELEASED"),
                        tuple(101L, engine, "RESERVED"));
        // 100 이 풀어 준 엔진 1 을 기다리던 101 이 가져간다. 기다리기만 하던 타이어는 재고 변화 없음
        assertThat(stock(engine)).containsEntry("available", 0).containsEntry("reserved", 1);
        assertThat(stock(tire)).containsEntry("available", 2).containsEntry("reserved", 0);
        assertThat(
                        jdbc.queryForList(
                                "SELECT type, qty, ref_id FROM stock_history WHERE part_id = ? ORDER BY id",
                                engine))
                .extracting(h -> h.get("type"), h -> h.get("qty"), h -> h.get("ref_id"))
                .containsExactly(tuple("RELEASE", 1, 100L), tuple("RESERVE", 1, 101L));
        // 예약이 다 찬 101 은 outbox 에 inventory.reserved 1건
        assertThat(reservedProductionIds()).containsExactly(101L);
        assertReservedMatchesReservedLines();
    }

    @Test
    void sameCancelTwiceIsAppliedOnce() {
        listener.onProductionCancelled(cancelled(UUID.randomUUID(), 100L));
        listener.onProductionCancelled(cancelled(UUID.randomUUID(), 100L));

        assertThat(stock(engine)).containsEntry("available", 0).containsEntry("reserved", 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stock_history WHERE type = 'RELEASE'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(reservedProductionIds()).containsExactly(101L);
    }

    @Test
    void productionWithoutReservationLinesChangesNothing() {
        listener.onProductionCancelled(cancelled(UUID.randomUUID(), 999L));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void receivesCancelledFromRabbitMq() {
        send(cancelled(UUID.randomUUID(), 100L));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(reservedProductionIds()).containsExactly(101L));
    }

    private EventEnvelope cancelled(UUID eventId, long productionId) {
        return new EventEnvelope(
                eventId,
                "production.cancelled",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(Map.of("orderId", 42L, "productionId", productionId)));
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "production.cancelled",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private List<org.assertj.core.groups.Tuple> lines() {
        return jdbc
                .queryForList(
                        "SELECT production_id, part_id, status FROM part_reservation ORDER BY id")
                .stream()
                .map(
                        r ->
                                tuple(
                                        ((Number) r.get("production_id")).longValue(),
                                        ((Number) r.get("part_id")).longValue(),
                                        r.get("status")))
                .toList();
    }

    private List<Long> reservedProductionIds() {
        return jdbc
                .queryForList(
                        "SELECT payload FROM outbox WHERE event_name = 'inventory.reserved' ORDER BY id",
                        String.class)
                .stream()
                .map(p -> ((Number) JsonPath.read(p, "$.payload.productionId")).longValue())
                .toList();
    }

    private void assertReservedMatchesReservedLines() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT p.id FROM part p WHERE p.reserved <> (SELECT COALESCE(SUM(r.qty), 0)"
                                        + " FROM part_reservation r WHERE r.part_id = p.id"
                                        + " AND r.status = 'RESERVED')"))
                .isEmpty();
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

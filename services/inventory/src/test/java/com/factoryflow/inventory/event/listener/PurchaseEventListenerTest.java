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

/** purchase.received 수신으로 재고 증가와 대기 줄 재예약 (docs/specs/purchase-receiving.md "3. 입고"). */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
class PurchaseEventListenerTest {

    static final String DLQ = "inventory.purchase-received.dlq";

    @Autowired PurchaseEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    long engine;
    long tire;
    long seat;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM part_lot");
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM part_reservation");
        jdbc.update("DELETE FROM part");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        amqpAdmin.purgeQueue(DLQ, false);
        engine = insertPart("ENG-T", 0, 3);
        tire = insertPart("TIRE-T", 0, 0);
        seat = insertPart("SEAT-T", 0, 0);
        // 타이어를 기다리는 차 (먼저 온 순서): 100 이 4개, 101 이 6개, 102 가 1개. 엔진은 셋 다 잡아 두었다
        for (long production : List.of(100L, 101L, 102L)) {
            insertReservation(production, engine, 1, "RESERVED");
        }
        insertReservation(100L, tire, 4, "WAITING");
        insertReservation(101L, tire, 6, "WAITING");
        insertReservation(102L, tire, 1, "WAITING");
    }

    @Test
    void receiptFillsWaitingLinesInOrderAndAnnouncesCarsWithAllParts() {
        listener.onReceived(received(31L, tire, 11));

        assertThat(statuses(tire))
                .containsExactly(
                        tuple(100L, "RESERVED"), tuple(101L, "RESERVED"), tuple(102L, "RESERVED"));
        assertThat(stock(tire)).containsEntry("available", 0).containsEntry("reserved", 11);
        assertThat(
                        jdbc.queryForList(
                                "SELECT type, qty, ref_type, ref_id FROM stock_history"
                                        + " WHERE part_id = ? ORDER BY id",
                                tire))
                .extracting(
                        h -> h.get("type"),
                        h -> h.get("qty"),
                        h -> h.get("ref_type"),
                        h -> h.get("ref_id"))
                .containsExactly(
                        tuple("RECEIVE", 11, "PURCHASE", 31L),
                        tuple("RESERVE", 4, "PRODUCTION", 100L),
                        tuple("RESERVE", 6, "PRODUCTION", 101L),
                        tuple("RESERVE", 1, "PRODUCTION", 102L));
        assertThat(reservedProductionIds()).containsExactly(100L, 101L, 102L);
        assertReservedMatchesReservedLines();
    }

    @Test
    void aLineThatDoesNotFitStopsTheLinesBehindIt() {
        // 5개 입고: 100(4) 은 차고 1개가 남는다. 101(6) 은 안 들어가므로 거기서 멈춘다. 뒤의 102(1) 가 들어갈 수 있어도 새치기하지 않는다
        listener.onReceived(received(31L, tire, 5));

        assertThat(statuses(tire))
                .containsExactly(
                        tuple(100L, "RESERVED"), tuple(101L, "WAITING"), tuple(102L, "WAITING"));
        assertThat(stock(tire)).containsEntry("available", 1).containsEntry("reserved", 4);
        assertThat(reservedProductionIds()).containsExactly(100L);
        assertReservedMatchesReservedLines();
    }

    @Test
    void carStillWaitingForAnotherPartIsNotAnnounced() {
        insertReservation(100L, seat, 1, "WAITING");

        listener.onReceived(received(31L, tire, 4));

        assertThat(statuses(tire)).first().isEqualTo(tuple(100L, "RESERVED"));
        assertThat(reservedProductionIds()).isEmpty();

        listener.onReceived(received(32L, seat, 1));

        assertThat(reservedProductionIds()).containsExactly(100L);
    }

    @Test
    void receiptWithNoWaitingLineOnlyAddsStock() {
        listener.onReceived(received(31L, seat, 7));

        assertThat(stock(seat)).containsEntry("available", 7).containsEntry("reserved", 0);
        assertThat(reservedProductionIds()).isEmpty();
    }

    @Test
    void sameReceiptIsAppliedOnce() {
        EventEnvelope event = received(31L, tire, 5);

        listener.onReceived(event);
        listener.onReceived(event);
        // eventId 만 다른 재발행도 같은 구매 건이면 한 번만
        listener.onReceived(received(31L, tire, 5));

        assertThat(stock(tire)).containsEntry("available", 1).containsEntry("reserved", 4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stock_history WHERE type = 'RECEIVE'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(reservedProductionIds()).containsExactly(100L);
    }

    @Test
    void receivesFromRabbitMq() {
        send(received(31L, tire, 4));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(reservedProductionIds()).containsExactly(100L));
    }

    @Test
    void unknownPartIsDeadLettered() {
        EventEnvelope event = received(31L, 999_999L, 4);

        send(event);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(DLQ).getMessageCount() == 1);
        Message dead = rabbitTemplate.receive(DLQ);
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history", Integer.class))
                .isZero();
    }

    private EventEnvelope received(long purchaseId, long partId, int qty) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "purchase.received",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of("purchaseId", purchaseId, "partId", partId, "qty", qty)));
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "purchase.received",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private List<org.assertj.core.groups.Tuple> statuses(long partId) {
        return jdbc
                .queryForList(
                        "SELECT production_id, status FROM part_reservation WHERE part_id = ? ORDER BY id",
                        partId)
                .stream()
                .map(r -> tuple(((Number) r.get("production_id")).longValue(), r.get("status")))
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

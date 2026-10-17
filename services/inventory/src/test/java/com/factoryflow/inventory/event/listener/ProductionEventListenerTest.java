package com.factoryflow.inventory.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.inventory.service.PartService;
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
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** production.material-requested 수신으로 부품 예약 (docs/specs/inventory-reservation.md). */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
class ProductionEventListenerTest {

    static final String DLQ = "inventory.production-material-requested.dlq";

    @Autowired ProductionEventListener listener;
    @Autowired PartService partService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    long engine;
    long tire;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM part_reservation");
        jdbc.update("DELETE FROM part");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        amqpAdmin.purgeQueue(DLQ, false);
        engine = insertPart("ENG-T", "테스트 엔진", 10);
        tire = insertPart("TIRE-T", "테스트 타이어", 3);
    }

    @Test
    void enoughStockReservesAllParts() {
        listener.onMaterialRequested(
                materialRequested(100L, List.of(line(engine, 4), line(tire, 2))));

        assertThat(reservations())
                .extracting(r -> r.get("part_id"), r -> r.get("qty"), r -> r.get("status"))
                .containsExactlyInAnyOrder(
                        tuple(engine, 4, "RESERVED"), tuple(tire, 2, "RESERVED"));
        assertThat(stock(engine)).containsEntry("available", 6).containsEntry("reserved", 4);
        assertThat(stock(tire)).containsEntry("available", 1).containsEntry("reserved", 2);
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
                        tuple(engine, 4, "RESERVE", "PRODUCTION", 100L),
                        tuple(tire, 2, "RESERVE", "PRODUCTION", 100L));
        assertThat(outbox("inventory.reserved"))
                .singleElement()
                .satisfies(p -> assertThat(number(p, "$.payload.productionId")).isEqualTo(100L));
        assertThat(outbox("inventory.reservation-pending")).isEmpty();
        assertThat(outbox("inventory.shortage")).isEmpty();
        assertReservedMatchesReservedLines();
    }

    @Test
    void shortPartWaitsAndPublishesPendingWithoutAShortageEvent() {
        listener.onMaterialRequested(
                materialRequested(100L, List.of(line(engine, 4), line(tire, 5))));

        assertThat(reservations())
                .extracting(r -> r.get("part_id"), r -> r.get("qty"), r -> r.get("status"))
                .containsExactlyInAnyOrder(tuple(engine, 4, "RESERVED"), tuple(tire, 5, "WAITING"));
        assertThat(stock(tire)).containsEntry("available", 3).containsEntry("reserved", 0);
        assertThat(jdbc.queryForList("SELECT part_id FROM stock_history", Long.class))
                .containsExactly(engine);

        assertThat(outbox("inventory.reserved")).isEmpty();
        String pending = outbox("inventory.reservation-pending").get(0);
        assertThat(number(pending, "$.payload.productionId")).isEqualTo(100L);
        assertThat(JsonPath.<List<Integer>>read(pending, "$.payload.shortages[*].partId"))
                .containsExactly((int) tire);
        assertThat(JsonPath.<List<Integer>>read(pending, "$.payload.shortages[*].shortQty"))
                .containsExactly(5);

        // 자동 구매는 없다. 구매 담당이 부족 부품 조회로 본다 (docs/specs/purchase-manual.md)
        assertThat(outbox("inventory.shortage")).isEmpty();
        assertThat(partService.findShortages())
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.partId()).isEqualTo(tire);
                            assertThat(s.code()).isEqualTo("TIRE-T");
                            assertThat(s.name()).isEqualTo("테스트 타이어");
                            assertThat(s.shortQty()).isEqualTo(2);
                        });
        assertReservedMatchesReservedLines();
    }

    @Test
    void newRequestQueuesBehindWaitingLineEvenWithStock() {
        insertReservation(50L, engine, 20, "WAITING");

        listener.onMaterialRequested(materialRequested(100L, List.of(line(engine, 1))));

        assertThat(statusOf(100L, engine)).isEqualTo("WAITING");
        assertThat(stock(engine)).containsEntry("available", 10).containsEntry("reserved", 0);
        // 기다리는 수량 20 + 1 = 21, 재고 10 → 부족 11
        assertThat(partService.findShortages())
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.waitingQty()).isEqualTo(21);
                            assertThat(s.shortQty()).isEqualTo(11);
                            assertThat(s.waitingProductions()).isEqualTo(2);
                        });
    }

    @Test
    void shortQtyIsWhatTheWaitingLinesStillNeed() {
        // available 3, 대기 2, 새 요청 2 → 기다리는 수량 4 − 재고 3 = 부족 1
        insertReservation(50L, tire, 2, "WAITING");

        listener.onMaterialRequested(materialRequested(100L, List.of(line(tire, 2))));

        assertThat(partService.findShortages())
                .singleElement()
                .satisfies(s -> assertThat(s.shortQty()).isEqualTo(1));
    }

    @Test
    void partIsNotShortWhenStockCoversTheWaitingLines() {
        insertReservation(50L, engine, 2, "WAITING");

        listener.onMaterialRequested(materialRequested(100L, List.of(line(engine, 3))));

        assertThat(statusOf(100L, engine)).isEqualTo("WAITING");
        assertThat(outbox("inventory.reservation-pending")).hasSize(1);
        // 기다리는 수량 5 ≤ 재고 10 이라 부족 부품이 아니다
        assertThat(partService.findShortages()).isEmpty();
    }

    @Test
    void everyRequestedPartBumpsItsVersionSoConcurrentRequestsConflict() {
        // WAITING 경로는 Part 를 바꾸지 않아도 version 이 올라야 한다. 그래야 동시에 같은 부품을 처리한 다른 요청이
        // 커밋에서 충돌하고, 재시도 때 이 WAITING 줄을 보고 뒤에 선다
        long engineBefore = version(engine);
        long tireBefore = version(tire);

        listener.onMaterialRequested(
                materialRequested(100L, List.of(line(engine, 4), line(tire, 5))));

        assertThat(statusOf(100L, tire)).isEqualTo("WAITING");
        // 값이 바뀐 엔진은 변경과 강제 증가로 두 번 오를 수 있다. 충돌 감지에는 "올랐는지" 만 중요하다
        assertThat(version(engine)).isGreaterThan(engineBefore);
        assertThat(version(tire)).isGreaterThan(tireBefore);
    }

    @Test
    void samePartTwiceInOneEventIsMergedIntoOneLine() {
        listener.onMaterialRequested(
                materialRequested(100L, List.of(line(engine, 1), line(engine, 2))));

        assertThat(reservations())
                .extracting(r -> r.get("part_id"), r -> r.get("qty"))
                .containsExactly(tuple(engine, 3));
        assertThat(stock(engine)).containsEntry("available", 7).containsEntry("reserved", 3);
    }

    @Test
    void sameEventTwiceChangesStockOnce() {
        EventEnvelope event = materialRequested(100L, List.of(line(engine, 4)));

        listener.onMaterialRequested(event);
        listener.onMaterialRequested(event);

        assertThat(reservations()).hasSize(1);
        assertThat(stock(engine)).containsEntry("available", 6).containsEntry("reserved", 4);
        assertThat(outbox("inventory.reserved")).hasSize(1);
    }

    @Test
    void sameProductionWithAnotherEventIdChangesStockOnce() {
        listener.onMaterialRequested(materialRequested(100L, List.of(line(engine, 4))));
        listener.onMaterialRequested(materialRequested(100L, List.of(line(engine, 4))));

        assertThat(reservations()).hasSize(1);
        assertThat(stock(engine)).containsEntry("available", 6).containsEntry("reserved", 4);
        assertThat(outbox("inventory.reserved")).hasSize(1);
    }

    @Test
    void publishedEventsKeepTheReceivedTraceId() {
        EventEnvelope event = materialRequested(100L, List.of(line(engine, 4), line(tire, 5)));

        listener.onMaterialRequested(event);

        List<String> traceIds =
                jdbc.queryForList("SELECT payload FROM outbox", String.class).stream()
                        .map(p -> JsonPath.<String>read(p, "$.traceId"))
                        .toList();
        // 나가는 이벤트는 예약 결과(reservation-pending) 하나다 (부족 알림 inventory.shortage 는 없앰)
        assertThat(traceIds).hasSize(1).containsOnly(event.traceId());
    }

    @Test
    void receivesMaterialRequestedFromRabbitMq() {
        EventEnvelope event = materialRequested(200L, List.of(line(engine, 1)));

        send(event);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(200L, engine)).isEqualTo("RESERVED"));
    }

    @Test
    void missingPartsFailTheReservationWithoutSavingAnything() {
        // BOM 에 있는 부품이 없거나 삭제됐다: DLQ 로 보내지 않고 reservation-failed 로 알린다 (docs/specs/stalled-flows.md
        // S18)
        long seat = insertPart("SEAT-T", "테스트 시트", 5);
        jdbc.update("UPDATE part SET is_deleted = TRUE WHERE id = ?", seat);

        listener.onMaterialRequested(
                materialRequested(
                        300L, List.of(line(engine, 1), line(999_999L, 1), line(seat, 2))));

        assertThat(reservations()).isEmpty();
        assertThat(stock(engine)).containsEntry("available", 10).containsEntry("reserved", 0);
        assertThat(outbox("inventory.reserved")).isEmpty();
        assertThat(outbox("inventory.reservation-pending")).isEmpty();
        assertThat(outbox("inventory.reservation-failed"))
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(number(e, "$.payload.productionId")).isEqualTo(300L);
                            assertThat(JsonPath.<String>read(e, "$.payload.reason"))
                                    .isEqualTo("PART_NOT_FOUND");
                            assertThat(JsonPath.<List<Number>>read(e, "$.payload.partIds"))
                                    .extracting(Number::longValue)
                                    .containsExactly(seat, 999_999L);
                        });
    }

    @Test
    void missingPartFromRabbitMqIsNotDeadLettered() {
        send(materialRequested(300L, List.of(line(engine, 1), line(999_999L, 1))));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(outbox("inventory.reservation-failed")).hasSize(1));
        assertThat(amqpAdmin.getQueueInfo(DLQ).getMessageCount()).isZero();
    }

    private EventEnvelope materialRequested(long productionId, List<Map<String, Object>> parts) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "production.material-requested",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(Map.of("productionId", productionId, "parts", parts)));
    }

    private static Map<String, Object> line(long partId, int qty) {
        return Map.of("partId", partId, "qty", qty);
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "production.material-requested",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private void assertReservedMatchesReservedLines() {
        List<Map<String, Object>> mismatched =
                jdbc.queryForList(
                        "SELECT p.id FROM part p WHERE p.reserved <> (SELECT COALESCE(SUM(r.qty), 0)"
                                + " FROM part_reservation r WHERE r.part_id = p.id"
                                + " AND r.status = 'RESERVED')");
        assertThat(mismatched).isEmpty();
    }

    private List<Map<String, Object>> reservations() {
        return jdbc.queryForList(
                "SELECT production_id, part_id, qty, status FROM part_reservation");
    }

    private String statusOf(long productionId, long partId) {
        List<String> status =
                jdbc.queryForList(
                        "SELECT status FROM part_reservation WHERE production_id = ? AND part_id = ?",
                        String.class,
                        productionId,
                        partId);
        return status.isEmpty() ? null : status.get(0);
    }

    private long version(long partId) {
        return jdbc.queryForObject("SELECT version FROM part WHERE id = ?", Long.class, partId);
    }

    private Map<String, Object> stock(long partId) {
        return jdbc.queryForMap("SELECT available, reserved FROM part WHERE id = ?", partId);
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

    private long insertPart(String code, String name, int available) {
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, created_at, updated_at)"
                        + " VALUES (?, ?, 'EA', ?, NOW(6), NOW(6))",
                code,
                name,
                available);
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

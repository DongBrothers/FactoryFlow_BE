package com.factoryflow.purchase.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.test.container.TestContainersConfig;
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

/** inventory.shortage 수신으로 자동 구매 건 (docs/specs/purchase-receiving.md "1. 부족 → 자동 구매"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class InventoryEventListenerTest {

    static final long TIRE = 1012L;

    @Autowired InventoryEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM purchase");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
    }

    @Test
    void shortageCreatesARequestedAutoPurchaseWithSnapshot() {
        listener.onShortage(shortage(TIRE, 3));

        assertThat(purchases())
                .singleElement()
                .satisfies(
                        p ->
                                assertThat(p)
                                        .containsEntry("part_id", TIRE)
                                        .containsEntry("part_code", "TIRE-18")
                                        .containsEntry("part_name", "타이어 18인치")
                                        .containsEntry("qty", 3)
                                        .containsEntry("source", "AUTO")
                                        .containsEntry("status", "REQUESTED")
                                        .containsEntry("supplier_id", null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void anotherShortageOfTheSamePartIsAddedToTheRequestedPurchase() {
        listener.onShortage(shortage(TIRE, 3));
        listener.onShortage(shortage(TIRE, 4));
        listener.onShortage(shortage(1001L, 2));

        assertThat(purchases())
                .extracting(p -> p.get("part_id"), p -> p.get("qty"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(TIRE, 7),
                        org.assertj.core.groups.Tuple.tuple(1001L, 2));
    }

    @Test
    void orderedPurchaseIsNotChangedAndANewOneIsMade() {
        // 공급업체에 이미 보낸 발주의 수량은 바꾸지 않는다
        listener.onShortage(shortage(TIRE, 3));
        jdbc.update("UPDATE purchase SET status = 'ORDERED' WHERE part_id = ?", TIRE);

        listener.onShortage(shortage(TIRE, 4));

        assertThat(purchases())
                .extracting(p -> p.get("status"), p -> p.get("qty"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ORDERED", 3),
                        org.assertj.core.groups.Tuple.tuple("REQUESTED", 4));
    }

    @Test
    void sameEventTwiceIsAppliedOnce() {
        EventEnvelope event = shortage(TIRE, 3);

        listener.onShortage(event);
        listener.onShortage(event);

        assertThat(purchases())
                .singleElement()
                .satisfies(p -> assertThat(p).containsEntry("qty", 3));
    }

    @Test
    void receivesShortageFromRabbitMq() {
        send(shortage(TIRE, 5));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(purchases()).hasSize(1));
    }

    private EventEnvelope shortage(long partId, int shortQty) {
        Map<Long, List<String>> names =
                Map.of(
                        TIRE,
                        List.of("TIRE-18", "타이어 18인치"),
                        1001L,
                        List.of("ENG-G16", "가솔린 엔진 1.6"));
        return new EventEnvelope(
                UUID.randomUUID(),
                "inventory.shortage",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of(
                                "partId",
                                partId,
                                "partCode",
                                names.get(partId).get(0),
                                "partName",
                                names.get(partId).get(1),
                                "shortQty",
                                shortQty)));
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "inventory.shortage",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private List<Map<String, Object>> purchases() {
        return jdbc.queryForList(
                "SELECT part_id, part_code, part_name, qty, source, status, supplier_id"
                        + " FROM purchase ORDER BY id");
    }
}

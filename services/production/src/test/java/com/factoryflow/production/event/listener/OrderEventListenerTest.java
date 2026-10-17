package com.factoryflow.production.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** order.created 수신으로 생산 건 생성 (docs/specs/production-order-created.md). */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms",
            // 재시도마다 "Retry attempt for operation" 를 남겨 시도 횟수를 셀 수 있게 한다
            "logging.level.org.springframework.core.retry=DEBUG"
        })
@Import(TestContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class OrderEventListenerTest {

    static final String DLQ = "production.order-created.dlq";

    @Autowired OrderEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    long carA;
    long carB;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM bom");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        carA = insertProduct("CAR-A", "소형차 A");
        carB = insertProduct("CAR-B", "소형차 B");
        insertBom(carA, 1001, 1);
        insertBom(carA, 1011, 4);
        insertBom(carA, 1018, 1);
        jdbc.update(
                "UPDATE bom SET is_deleted = TRUE WHERE product_id = ? AND part_id = 1018", carA);
        insertBom(carB, 1001, 1);
        insertBom(carB, 1012, 4);
        amqpAdmin.purgeQueue(DLQ, false);
    }

    @Test
    void createsOnePlannedProductionPerVehicleWithMaterialRequestAndStatusChanged() {
        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 1)));

        assertThat(productions())
                .extracting(
                        r -> r.get("order_id"),
                        r -> r.get("product_id"),
                        r -> r.get("seq"),
                        r -> r.get("status"))
                .containsExactlyInAnyOrder(
                        tuple(10L, carA, 1, "PLANNED"),
                        tuple(10L, carA, 2, "PLANNED"),
                        tuple(10L, carB, 1, "PLANNED"));

        Map<Long, String> requests = materialRequestsByProductionId();
        assertThat(requests).hasSize(3);
        for (long production : productionIdsOf(carA)) {
            assertThat(parts(requests.get(production)))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(1001, 1, 1011, 4));
        }
        assertThat(parts(requests.get(productionIdsOf(carB).get(0))))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1001, 1, 1012, 4));

        List<String> changed = outbox("production.status-changed");
        assertThat(changed).hasSize(3);
        assertThat(changed)
                .extracting(c -> JsonPath.<String>read(c, "$.payload.status"))
                .containsOnly("PLANNED");
        assertThat(changed)
                .extracting(c -> ((Number) JsonPath.read(c, "$.payload.productionId")).longValue())
                .containsExactlyInAnyOrderElementsOf(requests.keySet());
    }

    @Test
    void materialRequestKeepsTheTraceIdOfOrderCreated() {
        EventEnvelope event = orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 1));

        listener.onOrderCreated(event);

        List<String> traceIds =
                materialRequestsByProductionId().values().stream()
                        .map(p -> JsonPath.<String>read(p, "$.traceId"))
                        .toList();
        assertThat(traceIds).hasSize(3).containsOnly(event.traceId());
    }

    @Test
    void sameEventTwiceCreatesOnce() {
        EventEnvelope event = orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 1));

        listener.onOrderCreated(event);
        listener.onOrderCreated(event);

        assertThat(productions()).hasSize(3);
        assertThat(materialRequestsByProductionId()).hasSize(3);
    }

    @Test
    void sameOrderAndProductWithAnotherEventIdCreatesOnce() {
        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2)));
        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2)));

        assertThat(productions()).hasSize(2);
        assertThat(materialRequestsByProductionId()).hasSize(2);
    }

    @Test
    void sameProductTwiceInOneEventMakesVehiclesForTheMergedQty() {
        listener.onOrderCreated(
                orderCreated(
                        UUID.randomUUID(), 10L, List.of(List.of(carA, 1L), List.of(carA, 2L))));

        assertThat(productions())
                .extracting(r -> r.get("product_id"), r -> r.get("seq"))
                .containsExactlyInAnyOrder(tuple(carA, 1), tuple(carA, 2), tuple(carA, 3));
        assertThat(materialRequestsByProductionId()).hasSize(3);
    }

    @Test
    void productWithoutBomGetsCancelledVehiclesAndOthersAreCreated() {
        // 주문 뒤 BOM 이 지워졌다: 건너뛰지 않고 수량만큼 취소된 생산 건을 만들어 order 에 알린다 (docs/specs/stalled-flows.md S8)
        jdbc.update("UPDATE bom SET is_deleted = TRUE WHERE product_id = ?", carB);

        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 2)));

        assertRejectedProduct(carB, 2);
    }

    @Test
    void deletedProductGetsCancelledVehiclesAndOthersAreCreated() {
        jdbc.update("UPDATE product SET is_deleted = TRUE WHERE id = ?", carB);

        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 2)));

        assertRejectedProduct(carB, 2);
    }

    @Test
    void rejectedProductIsCancelledOnceWhenOrderCreatedComesAgain() {
        jdbc.update("UPDATE product SET is_deleted = TRUE WHERE id = ?", carB);

        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 2)));
        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 2)));

        assertRejectedProduct(carB, 2);
    }

    /** 문제 제품은 수량만큼 CANCELLED 생산 건과 status-changed(CANCELLED) 만 있고 부품 요청은 없다. 다른 제품은 그대로 진행한다. */
    private void assertRejectedProduct(long productId, int qty) {
        assertThat(productions())
                .filteredOn(r -> r.get("product_id").equals(productId))
                .extracting(r -> r.get("seq"), r -> r.get("status"))
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.rangeClosed(1, qty)
                                .mapToObj(seq -> tuple(seq, "CANCELLED"))
                                .toList());
        assertThat(productions())
                .filteredOn(r -> r.get("product_id").equals(carA))
                .extracting(r -> r.get("status"))
                .containsExactly("PLANNED", "PLANNED");
        assertThat(materialRequestsByProductionId())
                .containsOnlyKeys(productionIdsOf(carA).toArray(Long[]::new));
        List<Long> rejected = productionIdsOf(productId);
        assertThat(outbox("production.status-changed"))
                .filteredOn(
                        c ->
                                rejected.contains(
                                        ((Number) JsonPath.read(c, "$.payload.productionId"))
                                                .longValue()))
                .hasSize(qty)
                .extracting(c -> JsonPath.<String>read(c, "$.payload.status"))
                .containsOnly("CANCELLED");
    }

    @Test
    void receivesOrderCreatedFromRabbitMq() {
        EventEnvelope event = orderCreated(UUID.randomUUID(), 20L, Map.of(carA, 1));
        sendToRabbit(event);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(productions())
                                        .extracting(
                                                r -> r.get("order_id"), r -> r.get("product_id"))
                                        .containsExactly(tuple(20L, carA)));
    }

    @Test
    void failingMessageIsRetriedThenDeadLettered(CapturedOutput output) {
        // 수량 0 은 처리할 때마다 INVALID_ORDER_QTY 오류가 난다
        EventEnvelope event = orderCreated(UUID.randomUUID(), 30L, Map.of(carA, 0));

        sendToRabbit(event);

        Message dead = awaitDeadLetter();
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        assertThat(output.getOut())
                .contains(
                        "EVENT_DEAD_LETTERED queue=production.order-created eventId="
                                + event.eventId());
        // 처음 1번 + 재시도 3번
        assertThat(countLines(output.getOut(), "Retry attempt for operation", "StatelessRetry"))
                .isEqualTo(3);
        assertThat(productions()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
        assertThat(amqpAdmin.getQueueInfo("production.order-created").getMessageCount()).isZero();
    }

    @Test
    void brokenJsonIsDeadLettered(CapturedOutput output) {
        String messageId = UUID.randomUUID().toString();
        rabbitTemplate.send(
                "factoryflow.events",
                "order.created",
                MessageBuilder.withBody("{ not json".getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setMessageId(messageId)
                        .build());

        Message dead = awaitDeadLetter();
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(messageId);
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("{ not json");
        // 변환 실패도 재시도 대상: 처음 1번 + 재시도 3번
        assertThat(countLines(output.getOut(), "Retry attempt for operation", "StatelessRetry"))
                .isEqualTo(3);
        assertThat(output.getOut())
                .contains(
                        "EVENT_DEAD_LETTERED queue=production.order-created eventId=" + messageId);
        assertThat(productions()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class))
                .isZero();
    }

    private Message awaitDeadLetter() {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(DLQ).getMessageCount() == 1);
        return rabbitTemplate.receive(DLQ);
    }

    private void sendToRabbit(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "order.created",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private static long countLines(String text, String... parts) {
        return text.lines()
                .filter(line -> List.of(parts).stream().allMatch(line::contains))
                .count();
    }

    private EventEnvelope orderCreated(
            UUID eventId, long orderId, Map<Long, Integer> qtyByProductId) {
        return orderCreated(
                eventId,
                orderId,
                qtyByProductId.entrySet().stream()
                        .map(e -> List.of(e.getKey(), (long) e.getValue()))
                        .toList());
    }

    /** products 는 [productId, qty] 쌍의 목록. 같은 제품을 여러 줄 넣을 수 있다. */
    private EventEnvelope orderCreated(UUID eventId, long orderId, List<List<Long>> products) {
        List<Map<String, Object>> lines =
                products.stream()
                        .map(p -> Map.<String, Object>of("productId", p.get(0), "qty", p.get(1)))
                        .toList();
        return new EventEnvelope(
                eventId,
                "order.created",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(Map.of("orderId", orderId, "products", lines)));
    }

    private List<Map<String, Object>> productions() {
        return jdbc.queryForList("SELECT id, order_id, product_id, seq, status FROM production");
    }

    private List<Long> productionIdsOf(long productId) {
        return jdbc.queryForList(
                "SELECT id FROM production WHERE product_id = ? ORDER BY seq",
                Long.class,
                productId);
    }

    private List<String> outbox(String eventName) {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = ?", String.class, eventName);
    }

    private Map<Long, String> materialRequestsByProductionId() {
        List<String> payloads =
                jdbc.queryForList(
                        "SELECT payload FROM outbox WHERE event_name = 'production.material-requested'",
                        String.class);
        return payloads.stream()
                .collect(
                        Collectors.toMap(
                                p ->
                                        ((Number) JsonPath.read(p, "$.payload.productionId"))
                                                .longValue(),
                                p -> p));
    }

    private static Map<Integer, Integer> parts(String payload) {
        List<Integer> partIds = JsonPath.read(payload, "$.payload.parts[*].partId");
        List<Integer> qtys = JsonPath.read(payload, "$.payload.parts[*].qty");
        Map<Integer, Integer> parts = new HashMap<>();
        for (int i = 0; i < partIds.size(); i++) {
            parts.put(partIds.get(i), qtys.get(i));
        }
        return parts;
    }

    private long insertProduct(String code, String name) {
        jdbc.update(
                "INSERT INTO product (code, name, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                code,
                name);
        return jdbc.queryForObject("SELECT id FROM product WHERE code = ?", Long.class, code);
    }

    private void insertBom(long productId, long partId, int qtyPerUnit) {
        jdbc.update(
                "INSERT INTO bom (product_id, part_id, qty_per_unit, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NOW(6), NOW(6))",
                productId,
                partId,
                qtyPerUnit);
    }
}

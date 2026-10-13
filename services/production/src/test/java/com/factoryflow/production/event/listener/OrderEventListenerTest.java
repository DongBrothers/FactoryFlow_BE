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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
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
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class OrderEventListenerTest {

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
    }

    @Test
    void createsPlannedProductionAndMaterialRequestPerProduct() {
        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 1)));

        assertThat(productions())
                .extracting(
                        r -> r.get("order_id"),
                        r -> r.get("product_id"),
                        r -> r.get("qty"),
                        r -> r.get("status"))
                .containsExactlyInAnyOrder(
                        tuple(10L, carA, 2, "PLANNED"), tuple(10L, carB, 1, "PLANNED"));

        Map<Long, String> requests = materialRequestsByProductionId();
        assertThat(requests).hasSize(2);
        assertThat(parts(requests.get(productionIdOf(carA))))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1001, 2, 1011, 8));
        assertThat(parts(requests.get(productionIdOf(carB))))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1001, 1, 1012, 4));
    }

    @Test
    void sameEventTwiceCreatesOnce() {
        EventEnvelope event = orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 1));

        listener.onOrderCreated(event);
        listener.onOrderCreated(event);

        assertThat(productions()).hasSize(2);
        assertThat(materialRequestsByProductionId()).hasSize(2);
    }

    @Test
    void sameOrderAndProductWithAnotherEventIdCreatesOnce() {
        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2)));
        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2)));

        assertThat(productions()).hasSize(1);
        assertThat(materialRequestsByProductionId()).hasSize(1);
    }

    @Test
    void sameProductTwiceInOneEventIsMergedIntoOneProduction() {
        listener.onOrderCreated(
                orderCreated(
                        UUID.randomUUID(), 10L, List.of(List.of(carA, 1L), List.of(carA, 2L))));

        assertThat(productions())
                .extracting(r -> r.get("product_id"), r -> r.get("qty"))
                .containsExactly(tuple(carA, 3));
        assertThat(parts(materialRequestsByProductionId().get(productionIdOf(carA))))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1001, 3, 1011, 12));
    }

    @Test
    void productWithoutBomIsSkippedAndOthersAreCreated() {
        jdbc.update("UPDATE bom SET is_deleted = TRUE WHERE product_id = ?", carB);

        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 1)));

        assertThat(productions()).extracting(r -> r.get("product_id")).containsExactly(carA);
        assertThat(materialRequestsByProductionId()).containsOnlyKeys(productionIdOf(carA));
    }

    @Test
    void deletedProductIsSkippedAndOthersAreCreated() {
        jdbc.update("UPDATE product SET is_deleted = TRUE WHERE id = ?", carB);

        listener.onOrderCreated(orderCreated(UUID.randomUUID(), 10L, Map.of(carA, 2, carB, 1)));

        assertThat(productions()).extracting(r -> r.get("product_id")).containsExactly(carA);
        assertThat(materialRequestsByProductionId()).containsOnlyKeys(productionIdOf(carA));
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
    void failingMessageIsRetriedThenDropped(CapturedOutput output) {
        // qty 0 은 production 의 CHECK (qty > 0) 에 걸려 처리할 때마다 DB 오류가 난다
        EventEnvelope event = orderCreated(UUID.randomUUID(), 30L, Map.of(carA, 0));

        sendToRabbit(event);

        String dropped = "EVENT_DROPPED queue=production.order-created eventId=" + event.eventId();
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getOut().contains(dropped));
        assertThat(
                        countLines(
                                output.getOut(),
                                "WARN",
                                "Check constraint 'ck_production_qty' is violated"))
                .isEqualTo(4);
        assertThat(productions()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
        assertThat(amqpAdmin.getQueueInfo("production.order-created").getMessageCount()).isZero();
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
        return jdbc.queryForList("SELECT id, order_id, product_id, qty, status FROM production");
    }

    private long productionIdOf(long productId) {
        return jdbc.queryForObject(
                "SELECT id FROM production WHERE product_id = ?", Long.class, productId);
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

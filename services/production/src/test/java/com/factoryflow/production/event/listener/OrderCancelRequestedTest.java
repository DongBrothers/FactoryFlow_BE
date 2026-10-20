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

/**
 * order.cancel-requested 수신으로 차량(생산 건)별 취소·거절 (docs/specs/order-cancel.md "2. 차량별 판단"). 작업지시를 발행한
 * 차는 시작 여부를 아는 mes 에 묻는다 (docs/specs/mes-split.md "4. 주문 취소").
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
class OrderCancelRequestedTest {

    static final String DLQ = "production.order-cancel-requested.dlq";
    static final long ORDER = 42L;

    @Autowired OrderEventListener listener;
    @Autowired ProductionService productionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        amqpAdmin.purgeQueue(DLQ, false);
    }

    @Test
    void carsBeforeReleaseAreCancelledReleasedCarsAreAskedToMesAndStartedCarsAreRejected() {
        long planned = production(1L, 1, "PLANNED");
        long waiting = production(1L, 2, "WAITING_MATERIAL");
        long ready = readyProduction(2L, 1);
        long released = releasedProduction(4L, 1);
        long started = releasedProduction(3L, 1);
        productionService.startProduction(started);
        jdbc.update("DELETE FROM outbox");

        listener.onCancelRequested(cancelRequested(UUID.randomUUID()));

        assertThat(statusOf(planned)).isEqualTo("CANCELLED");
        assertThat(statusOf(waiting)).isEqualTo("CANCELLED");
        assertThat(statusOf(ready)).isEqualTo("CANCELLED");
        // 발행한 차는 mes 의 답(취소·거절)을 기다린다
        assertThat(statusOf(released)).isEqualTo("READY");
        assertThat(statusOf(started)).isEqualTo("IN_PROGRESS");

        assertThat(outbox("production.cancelled"))
                .extracting(p -> number(p, "$.payload.productionId"))
                .containsExactlyInAnyOrder(planned, waiting, ready);
        assertThat(outbox("production.release-cancel-requested"))
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(number(p, "$.payload.productionId")).isEqualTo(released);
                            assertThat(number(p, "$.payload.orderId")).isEqualTo(ORDER);
                        });
        assertThat(outbox("production.cancelled"))
                .allSatisfy(p -> assertThat(number(p, "$.payload.orderId")).isEqualTo(ORDER));
        String rejected = outbox("production.cancel-rejected").get(0);
        assertThat(number(rejected, "$.payload.productionId")).isEqualTo(started);
        assertThat(JsonPath.<String>read(rejected, "$.payload.reason"))
                .isEqualTo("ALREADY_STARTED");
        assertThat(JsonPath.<String>read(rejected, "$.payload.status")).isEqualTo("IN_PROGRESS");
        assertThat(outbox("production.cancel-rejected")).hasSize(1);
    }

    @Test
    void alreadyCancelledProductionSendsCancelledAgainAndTheSameMessageIsAppliedOnce() {
        long planned = production(1L, 1, "PLANNED");
        EventEnvelope event = cancelRequested(UUID.randomUUID());

        listener.onCancelRequested(event);
        listener.onCancelRequested(event);
        assertThat(outbox("production.cancelled")).hasSize(1);

        // eventId 만 다른 재요청: 이미 취소된 건이라 cancelled 를 다시 보낸다 (받는 쪽은 여러 번 받아도 안전)
        listener.onCancelRequested(cancelRequested(UUID.randomUUID()));

        assertThat(statusOf(planned)).isEqualTo("CANCELLED");
        assertThat(outbox("production.cancelled")).hasSize(2);
    }

    @Test
    void orderWithoutProductionsIsDeadLettered() {
        EventEnvelope event = cancelRequested(UUID.randomUUID());

        send(event);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(DLQ).getMessageCount() == 1);
        Message dead = rabbitTemplate.receive(DLQ);
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
    }

    @Test
    void receivesCancelRequestedFromRabbitMq() {
        long planned = production(1L, 1, "PLANNED");

        send(cancelRequested(UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(planned)).isEqualTo("CANCELLED"));
    }

    private long production(long productId, int seq, String status) {
        jdbc.update(
                "INSERT INTO production (order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                ORDER,
                productId,
                seq,
                status);
        return jdbc.queryForObject(
                "SELECT id FROM production WHERE order_id = ? AND product_id = ? AND seq = ?",
                Long.class,
                ORDER,
                productId,
                seq);
    }

    /** 부품이 다 잡혀 작업지시 발행을 기다리는 차. */
    private long readyProduction(long productId, int seq) {
        long production = production(productId, seq, "PLANNED");
        productionService.markReserved(production);
        return production;
    }

    /** 생산 담당이 작업지시를 발행한 차 (작업지시는 mes 가 만든다). */
    private long releasedProduction(long productId, int seq) {
        long production = readyProduction(productId, seq);
        productionService.release(production);
        return production;
    }

    private String statusOf(long production) {
        return jdbc.queryForObject(
                "SELECT status FROM production WHERE id = ?", String.class, production);
    }

    private EventEnvelope cancelRequested(UUID eventId) {
        return new EventEnvelope(
                eventId,
                "order.cancel-requested",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(Map.of("orderId", ORDER)));
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                "order.cancel-requested",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
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

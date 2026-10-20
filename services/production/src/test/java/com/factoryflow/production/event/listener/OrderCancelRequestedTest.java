package com.factoryflow.production.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.scheduler.LineDispatcher;
import com.factoryflow.production.service.ProductionService;
import com.factoryflow.production.service.WorkOrderService;
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

/** order.cancel-requested 수신으로 차량(생산 건)별 취소·거절 (docs/specs/order-cancel.md "2. 차량별 판단"). */
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
    @Autowired WorkOrderService workOrderService;
    @Autowired LineDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL");
        amqpAdmin.purgeQueue(DLQ, false);
    }

    @Test
    void carsBeforeThePressAreCancelledAndStartedCarsAreRejected() {
        long planned = production(1L, 1, "PLANNED");
        long waiting = production(1L, 2, "WAITING_MATERIAL");
        long ready = readyProduction(2L, 1);
        long started = readyProduction(3L, 1);
        workOrderService.startStep(workOrderOf(started), ProductionProcess.PRESS, "PRESS-01");
        jdbc.update("DELETE FROM outbox");

        listener.onCancelRequested(cancelRequested(UUID.randomUUID()));

        assertThat(statusOf(planned)).isEqualTo("CANCELLED");
        assertThat(statusOf(waiting)).isEqualTo("CANCELLED");
        assertThat(statusOf(ready)).isEqualTo("CANCELLED");
        assertThat(workOrderStatus(ready)).isEqualTo("CANCELLED");
        assertThat(statusOf(started)).isEqualTo("IN_PROGRESS");
        assertThat(workOrderStatus(started)).isEqualTo("IN_PROGRESS");

        assertThat(outbox("production.cancelled"))
                .extracting(p -> number(p, "$.payload.productionId"))
                .containsExactlyInAnyOrder(planned, waiting, ready);
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
    void cancelledWorkOrderIsNotDispatched() {
        long ready = readyProduction(1L, 1);
        jdbc.update(
                "UPDATE work_order SET released_at = NOW(6) - INTERVAL 10 MINUTE WHERE production_id = ?",
                ready);

        listener.onCancelRequested(cancelRequested(UUID.randomUUID()));
        dispatcher.dispatchAll();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM work_order_step WHERE status = 'DISPATCHED'",
                                Integer.class))
                .isZero();
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

    private long readyProduction(long productId, int seq) {
        long production = production(productId, seq, "PLANNED");
        productionService.markReserved(production);
        // 작업지시는 생산 담당이 발행한다 (docs/specs/work-order-release.md)
        workOrderService.release(production);
        return production;
    }

    private long workOrderOf(long production) {
        return jdbc.queryForObject(
                "SELECT id FROM work_order WHERE production_id = ?", Long.class, production);
    }

    private String statusOf(long production) {
        return jdbc.queryForObject(
                "SELECT status FROM production WHERE id = ?", String.class, production);
    }

    private String workOrderStatus(long production) {
        return jdbc.queryForObject(
                "SELECT status FROM work_order WHERE production_id = ?", String.class, production);
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

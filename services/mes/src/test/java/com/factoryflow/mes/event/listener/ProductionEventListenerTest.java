package com.factoryflow.mes.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.scheduler.LineDispatcher;
import com.factoryflow.mes.service.WorkOrderService;
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

/** production 이 넘긴 작업지시 발행과 발행한 차의 취소 요청 (docs/specs/mes-split.md "1. 작업지시 발행", "4. 주문 취소"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class ProductionEventListenerTest {

    static final long ORDER = 42L;
    static final long PRODUCT = 1L;

    @Autowired ProductionEventListener listener;
    @Autowired WorkOrderService workOrderService;
    @Autowired LineDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;

    long nextProductionId = 500;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL");
    }

    @Test
    void releaseCreatesTheWorkOrderWithFiveStepsInRoutingOrder() {
        long production = nextProductionId++;

        listener.onReleased(released(production));

        Map<String, Object> workOrder =
                jdbc.queryForMap(
                        "SELECT id, order_id, product_id, status FROM work_order"
                                + " WHERE production_id = ?",
                        production);
        assertThat(workOrder)
                .containsEntry("status", "RELEASED")
                .containsEntry("order_id", ORDER)
                .containsEntry("product_id", PRODUCT);
        long workOrderId = ((Number) workOrder.get("id")).longValue();
        assertThat(
                        jdbc.queryForList(
                                "SELECT process FROM work_order_step WHERE work_order_id = ?"
                                        + " ORDER BY seq",
                                String.class,
                                workOrderId))
                .containsExactly("PRESS", "BODY", "PAINT", "ASSEMBLY", "INSPECTION");
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT status FROM work_order_step WHERE work_order_id = ?",
                                String.class,
                                workOrderId))
                .containsExactly("PENDING");
        // 발행만으로는 이벤트가 없다. 첫 공정을 시작할 때 production 에 알린다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void sameReleaseTwiceKeepsOneWorkOrder() {
        long production = nextProductionId++;
        EventEnvelope event = released(production);

        listener.onReleased(event);
        listener.onReleased(event);
        // eventId 만 다른 재전송도 작업지시를 하나 더 만들지 않는다
        listener.onReleased(released(production));

        assertThat(workOrderCount(production)).isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM work_order_step s JOIN work_order w"
                                        + " ON w.id = s.work_order_id WHERE w.production_id = ?",
                                Integer.class,
                                production))
                .isEqualTo(5);
    }

    @Test
    void pressIsDispatchedOnlyAfterTheRelease() {
        long production = nextProductionId++;

        dispatcher.dispatchAll();
        assertThat(outbox("mes.step-dispatched")).isEmpty();

        listener.onReleased(released(production));
        dispatcher.dispatchAll();

        assertThat(stepStatus(production, "PRESS")).isEqualTo("DISPATCHED");
        assertThat(outbox("mes.step-dispatched")).hasSize(1);
    }

    @Test
    void cancelBeforeTheFirstStepCancelsTheWorkOrderAndItIsNotDispatched() {
        long production = nextProductionId++;
        listener.onReleased(released(production));

        listener.onReleaseCancelRequested(cancelRequested(production));
        dispatcher.dispatchAll();

        assertThat(workOrderStatus(production)).isEqualTo("CANCELLED");
        assertThat(stepStatus(production, "PRESS")).isEqualTo("PENDING");
        assertThat(outbox("mes.step-dispatched")).isEmpty();
        assertThat(outbox("mes.work-order-cancelled"))
                .singleElement()
                .satisfies(
                        p -> assertThat(number(p, "$.payload.productionId")).isEqualTo(production));
        assertThat(outbox("mes.work-order-cancel-rejected")).isEmpty();
    }

    @Test
    void cancelAfterTheFirstStepIsRejectedWithTheWorkOrderStatus() {
        long production = nextProductionId++;
        listener.onReleased(released(production));
        workOrderService.startStep(workOrderOf(production), ProductionProcess.PRESS, "PRESS-01");

        listener.onReleaseCancelRequested(cancelRequested(production));

        assertThat(workOrderStatus(production)).isEqualTo("IN_PROGRESS");
        String rejected = outbox("mes.work-order-cancel-rejected").get(0);
        assertThat(number(rejected, "$.payload.productionId")).isEqualTo(production);
        assertThat(JsonPath.<String>read(rejected, "$.payload.status")).isEqualTo("IN_PROGRESS");
        assertThat(outbox("mes.work-order-cancelled")).isEmpty();
    }

    @Test
    void cancelArrivingBeforeTheReleaseLeavesACancelledWorkOrderThatBlocksTheLateRelease() {
        // 큐가 달라 취소 요청이 발행보다 먼저 올 수 있다
        long production = nextProductionId++;

        listener.onReleaseCancelRequested(cancelRequested(production));
        listener.onReleased(released(production));
        dispatcher.dispatchAll();

        assertThat(workOrderCount(production)).isOne();
        assertThat(workOrderStatus(production)).isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM work_order_step s JOIN work_order w"
                                        + " ON w.id = s.work_order_id WHERE w.production_id = ?",
                                Integer.class,
                                production))
                .isZero();
        assertThat(outbox("mes.work-order-cancelled")).hasSize(1);
        assertThat(outbox("mes.step-dispatched")).isEmpty();
    }

    @Test
    void cancelOfAnAlreadyCancelledWorkOrderSendsCancelledAgain() {
        long production = nextProductionId++;
        listener.onReleased(released(production));

        listener.onReleaseCancelRequested(cancelRequested(production));
        listener.onReleaseCancelRequested(cancelRequested(production));

        assertThat(workOrderStatus(production)).isEqualTo("CANCELLED");
        assertThat(outbox("mes.work-order-cancelled")).hasSize(2);
    }

    @Test
    void receivesReleasedFromRabbitMq() {
        long production = nextProductionId++;

        send(released(production));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(workOrderCount(production)).isOne());
    }

    private EventEnvelope released(long production) {
        return event(
                "production.released",
                Map.of("productionId", production, "orderId", ORDER, "productId", PRODUCT));
    }

    private EventEnvelope cancelRequested(long production) {
        return event(
                "production.release-cancel-requested",
                Map.of("orderId", ORDER, "productionId", production));
    }

    private EventEnvelope event(String name, Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID(),
                name,
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(payload));
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                event.eventName(),
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private long workOrderOf(long production) {
        return jdbc.queryForObject(
                "SELECT id FROM work_order WHERE production_id = ?", Long.class, production);
    }

    private int workOrderCount(long production) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE production_id = ?",
                Integer.class,
                production);
    }

    private String workOrderStatus(long production) {
        return jdbc.queryForObject(
                "SELECT status FROM work_order WHERE production_id = ?", String.class, production);
    }

    private String stepStatus(long production, String process) {
        return jdbc.queryForObject(
                "SELECT s.status FROM work_order_step s JOIN work_order w ON w.id = s.work_order_id"
                        + " WHERE w.production_id = ? AND s.process = ?",
                String.class,
                production,
                process);
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

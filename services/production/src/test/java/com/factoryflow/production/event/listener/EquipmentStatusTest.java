package com.factoryflow.production.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.production.scheduler.LineDispatcher;
import com.factoryflow.production.service.ProductionService;
import com.factoryflow.production.service.WorkOrderService;
import com.factoryflow.test.container.TestContainersConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

/** 설비 상태 수신과 고장·정지 시 재배정 (docs/specs/equipment-status.md). */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
        })
@Import(TestContainersConfig.class)
class EquipmentStatusTest {

    static final String DLQ = "production.equipment-status-changed.dlq";
    static final String WORKER = "3f2a9c1e-0000-4000-8000-000000000001";

    @Autowired SimulatorEventListener listener;
    @Autowired LineDispatcher dispatcher;
    @Autowired ProductionService productionService;
    @Autowired WorkOrderService workOrderService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    long nextOrderId = 700;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM equipment_status_history");
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
    void statusChangeIsSavedWithHistory() {
        Instant occurredAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);

        listener.onStatusChanged(statusChanged("PAINT-02", "STOPPED", occurredAt));

        assertThat(equipmentStatus("PAINT-02")).isEqualTo("STOPPED");
        assertThat(history())
                .singleElement()
                .satisfies(
                        h ->
                                assertThat(h)
                                        .containsEntry("equipment_code", "PAINT-02")
                                        .containsEntry("from_status", "RUNNING")
                                        .containsEntry("to_status", "STOPPED"));
        // 이력의 시각은 처리 시각이 아니라 설비 상태가 바뀐 시각(이벤트 발생 시각)이다
        assertThat(
                        jdbc.queryForObject(
                                "SELECT changed_at FROM equipment_status_history",
                                LocalDateTime.class))
                .isEqualTo(LocalDateTime.ofInstant(occurredAt, ZoneId.systemDefault()));
    }

    @Test
    void sameStatusChangesNothing() {
        listener.onStatusChanged(statusChanged("PAINT-02", "RUNNING", Instant.now()));

        assertThat(equipmentStatus("PAINT-02")).isEqualTo("RUNNING");
        assertThat(history()).isEmpty();
    }

    @Test
    void faultSendsTheDispatchedStepBackAndFreesTheEquipment() {
        long workOrder = releasedWorkOrder();
        workOrderService.dispatch(workOrder, ProductionProcess.PRESS, "PRESS-01");
        int eventsBefore = outboxCount();

        listener.onStatusChanged(statusChanged("PRESS-01", "FAULT", Instant.now()));

        assertThat(step(workOrder, "PRESS"))
                .containsEntry("status", "PENDING")
                .containsEntry("equipment_code", null)
                .containsEntry("started_at", null);
        assertThat(equipmentStatus("PRESS-01")).isEqualTo("FAULT");
        assertThat(occupiedStepOf("PRESS-01")).isNull();
        // 차는 실패하지 않는다: 작업지시·생산 건 상태 그대로, 실적·이벤트 없음
        assertThat(workOrderStatus(workOrder)).isEqualTo("IN_PROGRESS");
        assertThat(productionStatusOf(workOrder)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_result", Integer.class))
                .isZero();
        assertThat(outboxCount()).isEqualTo(eventsBefore);
    }

    @Test
    void stoppedSendsTheWorkerStepInProgressBack() {
        long workOrder = releasedWorkOrder();
        workOrderService.startStep(workOrder, ProductionProcess.PRESS, "PRESS-02");

        listener.onStatusChanged(statusChanged("PRESS-02", "STOPPED", Instant.now()));

        assertThat(step(workOrder, "PRESS"))
                .containsEntry("status", "PENDING")
                .containsEntry("equipment_code", null);
        assertThat(occupiedStepOf("PRESS-02")).isNull();
        // 작업자는 다른 설비로 다시 시작한다
        workOrderService.startStep(workOrder, ProductionProcess.PRESS, "PRESS-01");
        assertThat(step(workOrder, "PRESS")).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void stepSentBackIsDispatchedToAnotherEquipment() {
        long workOrder = releasedWorkOrder();
        workOrderService.dispatch(workOrder, ProductionProcess.PRESS, "PRESS-01");
        listener.onStatusChanged(statusChanged("PRESS-01", "FAULT", Instant.now()));

        dispatcher.dispatchAll();

        assertThat(step(workOrder, "PRESS"))
                .containsEntry("status", "DISPATCHED")
                .containsEntry("equipment_code", "PRESS-02");
    }

    @Test
    void lateReportsFromBeforeTheFaultAreIgnored() {
        long workOrder = releasedWorkOrder();
        workOrderService.dispatch(workOrder, ProductionProcess.PRESS, "PRESS-01");
        listener.onStatusChanged(statusChanged("PRESS-01", "FAULT", Instant.now()));

        listener.onStepStarted(
                envelope(
                        "equipment.step-started",
                        Map.of(
                                "equipmentCode", "PRESS-01",
                                "workOrderId", workOrder,
                                "process", "PRESS"),
                        Instant.now()));
        listener.onStepCompleted(
                envelope(
                        "equipment.step-completed",
                        Map.of(
                                "equipmentCode", "PRESS-01",
                                "workOrderId", workOrder,
                                "process", "PRESS",
                                "result", "GOOD"),
                        Instant.now()));

        assertThat(step(workOrder, "PRESS")).containsEntry("status", "PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_result", Integer.class))
                .isZero();
    }

    @Test
    void runningAgainOnlyChangesStatus() {
        long workOrder = releasedWorkOrder();
        workOrderService.dispatch(workOrder, ProductionProcess.PRESS, "PRESS-01");
        listener.onStatusChanged(statusChanged("PRESS-01", "FAULT", Instant.now()));

        listener.onStatusChanged(statusChanged("PRESS-01", "RUNNING", Instant.now()));

        assertThat(equipmentStatus("PRESS-01")).isEqualTo("RUNNING");
        assertThat(step(workOrder, "PRESS")).containsEntry("status", "PENDING");
        assertThat(history())
                .extracting(h -> h.get("from_status"), h -> h.get("to_status"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("RUNNING", "FAULT"),
                        org.assertj.core.groups.Tuple.tuple("FAULT", "RUNNING"));
    }

    @Test
    void sameEventTwiceIsAppliedOnce() {
        EventEnvelope event = statusChanged("BODY-01", "FAULT", Instant.now());
        listener.onStatusChanged(event);
        // 두 번째가 "상태가 같으면 그대로" 규칙에 걸리지 않게 설비를 되돌려 둔다. 그래도 반영되지 않아야 ProcessedEventGuard 가 거른 것이다
        jdbc.update("UPDATE equipment SET status = 'RUNNING' WHERE code = 'BODY-01'");

        listener.onStatusChanged(event);

        assertThat(equipmentStatus("BODY-01")).isEqualTo("RUNNING");
        assertThat(history()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void equipmentHoldingAMissingStepIsStepNotFound() {
        // 정상 흐름에선 생기지 않는 데이터 꼬임이다. 원인이 보이게 STEP_NOT_FOUND 로 실패하고 아무것도 바뀌지 않는다
        jdbc.update("UPDATE equipment SET occupied_step_id = 999999 WHERE code = 'PRESS-01'");

        assertThatThrownBy(
                        () ->
                                listener.onStatusChanged(
                                        statusChanged("PRESS-01", "FAULT", Instant.now())))
                .isInstanceOfSatisfying(
                        CustomException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ProductionErrorCode.STEP_NOT_FOUND));

        assertThat(equipmentStatus("PRESS-01")).isEqualTo("RUNNING");
        assertThat(history()).isEmpty();
    }

    @Test
    void receivesStatusChangedFromRabbitMq() {
        send(statusChanged("BODY-02", "STOPPED", Instant.now()));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(equipmentStatus("BODY-02")).isEqualTo("STOPPED"));
    }

    @Test
    void unknownEquipmentIsDeadLettered() {
        EventEnvelope event =
                envelope(
                        "equipment.status-changed",
                        Map.of("equipmentCode", "PRESS-99", "process", "PRESS", "status", "FAULT"),
                        Instant.now());

        send(event);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(DLQ).getMessageCount() == 1);
        Message dead = rabbitTemplate.receive(DLQ);
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        assertThat(history()).isEmpty();
    }

    private long releasedWorkOrder() {
        long orderId = nextOrderId++;
        jdbc.update(
                "INSERT INTO production (order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (?, 1, 1, 'PLANNED', NOW(6), NOW(6))",
                orderId);
        long production =
                jdbc.queryForObject(
                        "SELECT id FROM production WHERE order_id = ?", Long.class, orderId);
        productionService.markReserved(production);
        return jdbc.queryForObject(
                "SELECT id FROM work_order WHERE production_id = ?", Long.class, production);
    }

    private EventEnvelope statusChanged(String equipmentCode, String status, Instant occurredAt) {
        return envelope(
                "equipment.status-changed",
                Map.of(
                        "equipmentCode", equipmentCode,
                        "process", equipmentCode.substring(0, equipmentCode.indexOf('-')),
                        "status", status),
                occurredAt);
    }

    private EventEnvelope envelope(String name, Map<String, Object> payload, Instant occurredAt) {
        return new EventEnvelope(
                UUID.randomUUID(),
                name,
                UUID.randomUUID().toString(),
                1,
                occurredAt,
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

    private List<Map<String, Object>> history() {
        return jdbc.queryForList(
                "SELECT equipment_code, from_status, to_status, changed_at"
                        + " FROM equipment_status_history ORDER BY id");
    }

    private String equipmentStatus(String code) {
        return jdbc.queryForObject(
                "SELECT status FROM equipment WHERE code = ?", String.class, code);
    }

    private Long occupiedStepOf(String code) {
        return jdbc.queryForObject(
                "SELECT occupied_step_id FROM equipment WHERE code = ?", Long.class, code);
    }

    private Map<String, Object> step(long workOrder, String process) {
        return jdbc.queryForMap(
                "SELECT status, equipment_code, started_at FROM work_order_step"
                        + " WHERE work_order_id = ? AND process = ?",
                workOrder,
                process);
    }

    private String workOrderStatus(long workOrder) {
        return jdbc.queryForObject(
                "SELECT status FROM work_order WHERE id = ?", String.class, workOrder);
    }

    private String productionStatusOf(long workOrder) {
        return jdbc.queryForObject(
                "SELECT p.status FROM production p JOIN work_order w ON w.production_id = p.id"
                        + " WHERE w.id = ?",
                String.class,
                workOrder);
    }

    private int outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
    }
}

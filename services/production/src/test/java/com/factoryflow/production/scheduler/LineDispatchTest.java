package com.factoryflow.production.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.event.listener.SimulatorEventListener;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.production.service.ProductionService;
import com.factoryflow.production.service.WorkOrderService;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 로봇 공정 자동 진행: 배정과 설비 보고 (docs/specs/production-work-order.md "3. 로봇 공정 자동 진행"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class LineDispatchTest {

    @Autowired LineDispatcher dispatcher;
    @Autowired SimulatorEventListener listener;
    @Autowired ProductionService productionService;
    @Autowired WorkOrderService workOrderService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    long nextOrderId = 500;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL");
    }

    @Test
    void pressIsDispatchedToFreeEquipmentInReleaseOrder() {
        long first = releasedWorkOrder();
        long second = releasedWorkOrder();
        long third = releasedWorkOrder();

        dispatcher.dispatchAll();

        assertThat(step(first, "PRESS")).containsEntry("status", "DISPATCHED");
        assertThat(step(second, "PRESS")).containsEntry("status", "DISPATCHED");
        assertThat(step(third, "PRESS")).containsEntry("status", "PENDING");
        assertThat(
                        List.of(
                                step(first, "PRESS").get("equipment_code"),
                                step(second, "PRESS").get("equipment_code")))
                .containsExactlyInAnyOrder("PRESS-01", "PRESS-02");
        List<String> dispatched = outbox("production.step-dispatched");
        assertThat(dispatched).hasSize(2);
        assertThat(JsonPath.<String>read(dispatched.get(0), "$.payload.process"))
                .isEqualTo("PRESS");
    }

    @Test
    void pressDispatchStartsProductionAndClosesTheCancelWindow() {
        long workOrder = releasedWorkOrder();

        dispatcher.dispatchAll();

        assertThat(workOrderStatus(workOrder)).isEqualTo("IN_PROGRESS");
        assertThat(productionStatusOf(workOrder)).isEqualTo("IN_PROGRESS");
        assertThat(outbox("production.status-changed"))
                .extracting(c -> JsonPath.<String>read(c, "$.payload.status"))
                .containsExactly("READY", "IN_PROGRESS");
    }

    @Test
    void pressIsDispatchedAsSoonAsTheWorkOrderIsReleased() {
        // 발행 직후에도 기다리지 않고 첫 배정 주기에 프레스가 배정된다 (취소할 틈을 두지 않는다)
        long workOrder = releasedWorkOrder();

        dispatcher.dispatchAll();

        assertThat(step(workOrder, "PRESS")).containsEntry("status", "DISPATCHED");
        assertThat(productionStatusOf(workOrder)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aCarThatKeepsFailingDoesNotBlockTheCarsBehindIt() {
        // 맨 앞 차는 생산 건이 취소됐는데 작업지시만 남아 배정할 때마다 실패한다 (WORK_ORDER_CLOSED)
        long broken = releasedWorkOrder();
        jdbc.update(
                "UPDATE production SET status = 'CANCELLED' WHERE id ="
                        + " (SELECT production_id FROM work_order WHERE id = ?)",
                broken);
        long second = releasedWorkOrder();
        long third = releasedWorkOrder();

        dispatcher.dispatchAll();

        assertThat(step(broken, "PRESS")).containsEntry("status", "PENDING");
        assertThat(step(second, "PRESS")).containsEntry("status", "DISPATCHED");
        assertThat(step(third, "PRESS")).containsEntry("status", "DISPATCHED");
    }

    @Test
    void stoppedEquipmentGetsNothing() {
        releasedWorkOrder();
        releasedWorkOrder();
        jdbc.update("UPDATE equipment SET status = 'STOPPED' WHERE code = 'PRESS-02'");

        dispatcher.dispatchAll();

        assertThat(outbox("production.step-dispatched")).hasSize(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT equipment_code FROM work_order_step WHERE status = 'DISPATCHED'",
                                String.class))
                .containsExactly("PRESS-01");
    }

    @Test
    void busyEquipmentGetsNoSecondCar() {
        releasedWorkOrder();
        releasedWorkOrder();
        releasedWorkOrder();
        dispatcher.dispatchAll();

        dispatcher.dispatchAll();

        assertThat(outbox("production.step-dispatched")).hasSize(2);
    }

    @Test
    void equipmentReportsMoveTheStepAndNextRobotStepIsDispatched() {
        long workOrder = releasedWorkOrder();
        dispatcher.dispatchAll();
        String press = (String) step(workOrder, "PRESS").get("equipment_code");

        listener.onStepStarted(started(workOrder, "PRESS", press));
        assertThat(step(workOrder, "PRESS")).containsEntry("status", "IN_PROGRESS");

        listener.onStepCompleted(completed(workOrder, "PRESS", press, "GOOD", null));
        assertThat(step(workOrder, "PRESS")).containsEntry("status", "DONE");
        Map<String, Object> result =
                jdbc.queryForMap(
                        "SELECT r.result, r.worker_id, r.equipment_code FROM production_result r"
                                + " JOIN work_order_step s ON s.id = r.work_order_step_id"
                                + " WHERE s.work_order_id = ?",
                        workOrder);
        assertThat(result)
                .containsEntry("result", "GOOD")
                .containsEntry("worker_id", null)
                .containsEntry("equipment_code", press);

        dispatcher.dispatchAll();
        assertThat(step(workOrder, "BODY")).containsEntry("status", "DISPATCHED");
    }

    @Test
    void defectReportSendsStepBackAndItIsDispatchedAgain() {
        long workOrder = releasedWorkOrder();
        dispatcher.dispatchAll();
        String press = (String) step(workOrder, "PRESS").get("equipment_code");
        listener.onStepStarted(started(workOrder, "PRESS", press));

        listener.onStepCompleted(completed(workOrder, "PRESS", press, "DEFECT", "찍힘"));

        assertThat(step(workOrder, "PRESS")).containsEntry("status", "PENDING");
        dispatcher.dispatchAll();
        assertThat(step(workOrder, "PRESS")).containsEntry("status", "DISPATCHED");
        // 재작업으로 다시 배정해도 생산 시작 알림은 처음 한 번뿐이다
        assertThat(outbox("production.status-changed"))
                .extracting(c -> JsonPath.<String>read(c, "$.payload.status"))
                .containsExactly("READY", "IN_PROGRESS");
    }

    @Test
    void manualProcessIsNotDispatched() {
        long workOrder = releasedWorkOrder();
        for (String process : List.of("PRESS", "BODY", "PAINT")) {
            dispatcher.dispatchAll();
            String equipment = (String) step(workOrder, process).get("equipment_code");
            listener.onStepStarted(started(workOrder, process, equipment));
            listener.onStepCompleted(completed(workOrder, process, equipment, "GOOD", null));
        }

        dispatcher.dispatchAll();

        assertThat(step(workOrder, "ASSEMBLY")).containsEntry("status", "PENDING");
        assertThat(outbox("production.step-dispatched")).hasSize(3);
    }

    @Test
    void mismatchedReportsAreIgnored() {
        long workOrder = releasedWorkOrder();
        dispatcher.dispatchAll();
        String press = (String) step(workOrder, "PRESS").get("equipment_code");
        String other = press.equals("PRESS-01") ? "PRESS-02" : "PRESS-01";

        // 완료가 시작보다 먼저 옴, 다른 설비의 보고, 배정되지 않은 공정의 보고
        listener.onStepCompleted(completed(workOrder, "PRESS", press, "GOOD", null));
        listener.onStepStarted(started(workOrder, "PRESS", other));
        listener.onStepStarted(started(workOrder, "BODY", "BODY-01"));

        assertThat(step(workOrder, "PRESS")).containsEntry("status", "DISPATCHED");
        assertThat(step(workOrder, "BODY")).containsEntry("status", "PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_result", Integer.class))
                .isZero();
    }

    @Test
    void sameReportTwiceIsAppliedOnce() {
        long workOrder = releasedWorkOrder();
        dispatcher.dispatchAll();
        String press = (String) step(workOrder, "PRESS").get("equipment_code");
        listener.onStepStarted(started(workOrder, "PRESS", press));
        EventEnvelope report = completed(workOrder, "PRESS", press, "DEFECT", "찍힘");

        listener.onStepCompleted(report);
        listener.onStepCompleted(report);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_result", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void closedWorkOrderIsNotDispatched() {
        long workOrder = releasedWorkOrder();
        jdbc.update("UPDATE work_order SET status = 'CANCELLED' WHERE id = ?", workOrder);

        dispatcher.dispatchAll();

        assertThat(step(workOrder, "PRESS")).containsEntry("status", "PENDING");
    }

    @Test
    void workerCannotStartADispatchedStep() {
        long workOrder = releasedWorkOrder();
        dispatcher.dispatchAll();

        assertThatThrownBy(
                        () ->
                                workOrderService.startStep(
                                        workOrder, ProductionProcess.PRESS, "PRESS-01"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ProductionErrorCode.INVALID_STEP_STATUS);
    }

    /** 생산 건을 만들고 예약 완료를 반영해 작업지시를 발행한다. */
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
        long workOrder =
                jdbc.queryForObject(
                        "SELECT id FROM work_order WHERE production_id = ?",
                        Long.class,
                        production);
        return workOrder;
    }

    private EventEnvelope started(long workOrder, String process, String equipmentCode) {
        return envelope(
                "equipment.step-started",
                Map.of(
                        "equipmentCode",
                        equipmentCode,
                        "workOrderId",
                        workOrder,
                        "process",
                        process));
    }

    private EventEnvelope completed(
            long workOrder, String process, String equipmentCode, String result, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("equipmentCode", equipmentCode);
        payload.put("workOrderId", workOrder);
        payload.put("process", process);
        payload.put("result", result);
        payload.put("defectReason", reason);
        return envelope("equipment.step-completed", payload);
    }

    private EventEnvelope envelope(String name, Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID(),
                name,
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(payload));
    }

    private Map<String, Object> step(long workOrder, String process) {
        return jdbc.queryForMap(
                "SELECT status, equipment_code FROM work_order_step WHERE work_order_id = ? AND process = ?",
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

    private List<String> outbox(String eventName) {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = ? ORDER BY id",
                String.class,
                eventName);
    }
}

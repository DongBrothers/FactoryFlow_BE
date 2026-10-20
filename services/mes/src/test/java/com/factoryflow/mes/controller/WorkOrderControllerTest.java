package com.factoryflow.mes.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.event.listener.payload.ProductionReleased;
import com.factoryflow.mes.exception.MesErrorCode;
import com.factoryflow.mes.repository.EquipmentRepository;
import com.factoryflow.mes.repository.WorkOrderRepository;
import com.factoryflow.mes.service.WorkOrderService;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** MES 작업지시·공정·실적 작업자 API (docs/specs/production-work-order.md). */
@SpringBootTest
@Import(TestContainersConfig.class)
class WorkOrderControllerTest {

    static final String WORKER = "3f2a9c1e-0000-4000-8000-000000000001";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderService workOrderService;
    @Autowired WorkOrderRepository workOrderRepository;
    @Autowired EquipmentRepository equipmentRepository;

    MockMvc mockMvc;
    long nextProductionId = 900;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL");
    }

    @Test
    void pressStartMovesStepAndWorkOrderToInProgressAndTellsProduction() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);

        start(workOrder, "PRESS", "PRESS-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.steps[0].process").value("PRESS"))
                .andExpect(jsonPath("$.data.steps[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.steps[0].equipmentCode").value("PRESS-01"));

        assertThat(workOrderStatus(workOrder)).isEqualTo("IN_PROGRESS");
        assertThat(productionStartedSent(workOrder)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                // 생산 시작 시각은 작업지시가 갖는다 (#123)
                                "SELECT started_at IS NOT NULL FROM work_order WHERE id = ?",
                                Boolean.class,
                                workOrder))
                .isTrue();
        // 생산 건 상태는 production 이 이 알림을 받아 IN_PROGRESS 로 바꾼다 (docs/specs/mes-split.md "2. 생산 시작")
        String started = outbox("mes.production-started").get(0);
        assertThat(JsonPath.<Integer>read(started, "$.payload.productionId")).isEqualTo(900);
        assertThat(((Number) JsonPath.read(started, "$.payload.workOrderId")).longValue())
                .isEqualTo(workOrder);
        assertThat(outbox("mes.production-started")).hasSize(1);
    }

    @Test
    void stepCannotStartBeforePreviousIsDone() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);

        start(workOrder, "BODY", "BODY-01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STEP_OUT_OF_ORDER"));
        assertThat(stepStatus(workOrder, "BODY")).isEqualTo("PENDING");
    }

    @Test
    void sameStepCannotStartTwice() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);
        start(workOrder, "PRESS", "PRESS-01").andExpect(status().isOk());

        start(workOrder, "PRESS", "PRESS-02")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STEP_STATUS"));
    }

    @Test
    void equipmentMustExistBelongToTheProcessAndRun() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);

        start(workOrder, "PRESS", "NOPE-99")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        start(workOrder, "PRESS", "BODY-01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_PROCESS_MISMATCH"));
        jdbc.update("UPDATE equipment SET status = 'FAULT' WHERE code = 'PRESS-01'");
        start(workOrder, "PRESS", "PRESS-01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_RUNNING"));

        assertThat(stepStatus(workOrder, "PRESS")).isEqualTo("PENDING");
        assertThat(productionStartedSent(workOrder)).isFalse();
    }

    @Test
    void busyEquipmentCannotTakeAnotherCar() throws Exception {
        long first = releasedWorkOrder(42L, 1L);
        long second = releasedWorkOrder(42L, 2L);
        start(first, "PRESS", "PRESS-01").andExpect(status().isOk());

        start(second, "PRESS", "PRESS-01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_BUSY"));
        start(second, "PRESS", "PRESS-02").andExpect(status().isOk());
    }

    @Test
    void equipmentIsFreeAgainWhenTheStepEnds() throws Exception {
        long first = releasedWorkOrder(42L, 1L);
        long second = releasedWorkOrder(42L, 2L);
        start(first, "PRESS", "PRESS-01");
        assertThat(occupiedStepOf("PRESS-01")).isNotNull();

        // 불량이면 재작업으로 돌아가면서 설비를 놓는다
        complete(first, "PRESS", "{\"result\":\"DEFECT\",\"defectReason\":\"찍힘\"}");
        assertThat(occupiedStepOf("PRESS-01")).isNull();
        start(second, "PRESS", "PRESS-01").andExpect(status().isOk());

        // 양품으로 끝나도 설비를 놓는다
        complete(second, "PRESS", "{\"result\":\"GOOD\"}");
        assertThat(occupiedStepOf("PRESS-01")).isNull();
        start(first, "PRESS", "PRESS-01").andExpect(status().isOk());
    }

    /**
     * 다른 차가 같은 설비를 먼저 잡고 커밋한 것처럼 만든다: 이 트랜잭션이 설비를 읽은 뒤 DB 의 설비 version 을 올리면, 이 트랜잭션의 점유는 낡은
     * version 으로 저장하려다 실패한다. 작업지시가 달라도 같은 설비를 두 차가 함께 잡을 수 없다.
     */
    @Test
    @Transactional
    void twoCarsCannotTakeTheSameEquipmentAtOnce() {
        long second = releasedWorkOrder(42L, 2L);
        equipmentRepository.findByCodeAndIsDeletedFalse("PRESS-01");
        jdbc.update("UPDATE equipment SET version = version + 1 WHERE code = 'PRESS-01'");

        assertThatThrownBy(
                        () ->
                                workOrderService.startStep(
                                        second, ProductionProcess.PRESS, "PRESS-01"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(MesErrorCode.MES_CONFLICT);
    }

    @Test
    void goodResultFinishesStepAndRecordsWorker() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);
        start(workOrder, "PRESS", "PRESS-01");

        complete(workOrder, "PRESS", "{\"result\":\"GOOD\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].status").value("DONE"))
                .andExpect(jsonPath("$.data.steps[0].results[0].result").value("GOOD"))
                .andExpect(jsonPath("$.data.steps[0].results[0].workerId").value(WORKER));

        assertThat(stepStatus(workOrder, "PRESS")).isEqualTo("DONE");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT r.result, r.worker_id, r.equipment_code FROM production_result r"
                                        + " JOIN work_order_step s ON s.id = r.work_order_step_id"
                                        + " WHERE s.work_order_id = ?",
                                workOrder))
                .containsEntry("result", "GOOD")
                .containsEntry("worker_id", WORKER)
                .containsEntry("equipment_code", "PRESS-01");
        start(workOrder, "BODY", "BODY-01").andExpect(status().isOk());
    }

    @Test
    void defectRecordsResultAndSendsStepBackForRework() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);
        start(workOrder, "PRESS", "PRESS-01");
        int eventsBefore = outbox("mes.production-started").size();

        complete(workOrder, "PRESS", "{\"result\":\"DEFECT\",\"defectReason\":\"찍힘\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.steps[0].results[0].defectReason").value("찍힘"));

        assertThat(stepStatus(workOrder, "PRESS")).isEqualTo("PENDING");
        assertThat(outbox("mes.production-started")).hasSize(eventsBefore);
        // 같은 공정을 다시 한다
        start(workOrder, "PRESS", "PRESS-02").andExpect(status().isOk());
        complete(workOrder, "PRESS", "{\"result\":\"GOOD\"}").andExpect(status().isOk());
        assertThat(
                        jdbc.queryForList(
                                "SELECT r.result FROM production_result r"
                                        + " JOIN work_order_step s ON s.id = r.work_order_step_id"
                                        + " WHERE s.work_order_id = ? ORDER BY r.id",
                                String.class,
                                workOrder))
                .containsExactly("DEFECT", "GOOD");
    }

    @Test
    void completeOnlyInProgressStep() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);

        complete(workOrder, "PRESS", "{\"result\":\"GOOD\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STEP_STATUS"));
    }

    @Test
    void allFiveStepsGoodLeavesEveryStepDone() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);

        for (ProductionProcess process : ProductionProcess.values()) {
            start(workOrder, process.name(), process.name() + "-01").andExpect(status().isOk());
            complete(workOrder, process.name(), "{\"result\":\"GOOD\"}").andExpect(status().isOk());
        }

        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT status FROM work_order_step WHERE work_order_id = ?",
                                String.class,
                                workOrder))
                .containsExactly("DONE");
    }

    @Test
    void closedWorkOrderRejectsSteps() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);
        jdbc.update("UPDATE work_order SET status = 'CANCELLED' WHERE id = ?", workOrder);

        start(workOrder, "PRESS", "PRESS-01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CLOSED"));
    }

    @Test
    void badRequestsAre400() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);
        start(workOrder, "PRESS", "PRESS-01");

        complete(workOrder, "PRESS", "{\"result\":\"DEFECT\"}").andExpect(status().isBadRequest());
        complete(workOrder, "PRESS", "{\"result\":\"SO_SO\"}").andExpect(status().isBadRequest());
        complete(workOrder, "PRESS", "{}").andExpect(status().isBadRequest());
        start(workOrder, "WELDING", "BODY-01").andExpect(status().isBadRequest());
        // 실적의 작업자(X-User-Id)가 없음
        mockMvc.perform(
                        post("/mes/work-orders/{id}/steps/PRESS/complete", workOrder)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"result\":\"GOOD\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/mes/work-orders").param("status", "COMPLETED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingWorkOrderIs404() throws Exception {
        start(999_999L, "PRESS", "PRESS-01")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
        mockMvc.perform(get("/mes/work-orders/{id}", 999_999L)).andExpect(status().isNotFound());
    }

    @Test
    void listsWorkOrdersByStatusWithCursor() throws Exception {
        long first = releasedWorkOrder(42L, 1L);
        long second = releasedWorkOrder(42L, 2L);
        long third = releasedWorkOrder(43L, 1L);
        start(third, "PRESS", "PRESS-01");

        mockMvc.perform(get("/mes/work-orders").param("status", "RELEASED").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.last").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(first))
                .andExpect(jsonPath("$.data.items[0].workOrderId").value(first))
                .andExpect(jsonPath("$.data.items[0].currentProcess").value("PRESS"));
        mockMvc.perform(
                        get("/mes/work-orders")
                                .param("status", "RELEASED")
                                .param("size", "1")
                                .param("lastId", String.valueOf(first)))
                .andExpect(jsonPath("$.data.items[0].workOrderId").value(second))
                .andExpect(jsonPath("$.data.last").value(true));
        mockMvc.perform(get("/mes/work-orders").param("status", "IN_PROGRESS"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].workOrderId").value(third));
    }

    @Test
    void detailShowsStepsInRoutingOrder() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);

        mockMvc.perform(get("/mes/work-orders/{id}", workOrder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrderId").value(workOrder))
                .andExpect(jsonPath("$.data.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.steps.length()").value(5))
                .andExpect(jsonPath("$.data.steps[4].process").value("INSPECTION"));
    }

    /**
     * 다른 트랜잭션이 먼저 커밋한 것처럼 만든다: 이 트랜잭션이 작업지시를 읽은 뒤 DB 의 version 을 올리면, 이 트랜잭션의 변경은 낡은 version 으로
     * 저장하려다 실패한다.
     */
    @Test
    @Transactional
    void concurrentChangeIsMesConflict() {
        long workOrder = releasedWorkOrder(42L, 1L);
        workOrderRepository.findById(workOrder);
        jdbc.update("UPDATE work_order SET version = version + 1 WHERE id = ?", workOrder);

        assertThatThrownBy(
                        () ->
                                workOrderService.startStep(
                                        workOrder, ProductionProcess.PRESS, "PRESS-01"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(MesErrorCode.MES_CONFLICT);
    }

    @Test
    void workOrderApiIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/mes/work-orders'].get.summary").exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/mes/work-orders/{workOrderId}/steps/{process}/start'].post.responses['409']")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/mes/work-orders/{workOrderId}/steps/{process}/complete'].post.responses['400']")
                                .exists());
    }

    private ResultActions start(long workOrder, String process, String equipmentCode)
            throws Exception {
        return mockMvc.perform(
                post("/mes/work-orders/{id}/steps/{process}/start", workOrder, process)
                        .header("X-User-Id", WORKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equipmentCode\":\"" + equipmentCode + "\"}"));
    }

    private ResultActions complete(long workOrder, String process, String body) throws Exception {
        return mockMvc.perform(
                post("/mes/work-orders/{id}/steps/{process}/complete", workOrder, process)
                        .header("X-User-Id", WORKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    /** production 이 발행한 차(production.released)를 받아 작업지시와 공정 5개를 만든다. */
    private long releasedWorkOrder(long orderId, long productId) {
        long production = nextProductionId++;
        workOrderService.release(new ProductionReleased(production, orderId, productId));
        return jdbc.queryForObject(
                "SELECT id FROM work_order WHERE production_id = ?", Long.class, production);
    }

    private Long occupiedStepOf(String equipmentCode) {
        return jdbc.queryForObject(
                "SELECT occupied_step_id FROM equipment WHERE code = ?", Long.class, equipmentCode);
    }

    private String workOrderStatus(long workOrder) {
        return jdbc.queryForObject(
                "SELECT status FROM work_order WHERE id = ?", String.class, workOrder);
    }

    /** production 에 생산 시작을 알렸는지 (mes.production-started, docs/specs/mes-split.md "2. 생산 시작"). */
    private boolean productionStartedSent(long workOrder) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox WHERE event_name = 'mes.production-started'"
                                + " AND JSON_EXTRACT(payload, '$.payload.workOrderId') = ?",
                        Integer.class,
                        workOrder)
                > 0;
    }

    private String stepStatus(long workOrder, String process) {
        return jdbc.queryForObject(
                "SELECT status FROM work_order_step WHERE work_order_id = ? AND process = ?",
                String.class,
                workOrder,
                process);
    }

    private List<String> outbox(String eventName) {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = ? ORDER BY id",
                String.class,
                eventName);
    }
}

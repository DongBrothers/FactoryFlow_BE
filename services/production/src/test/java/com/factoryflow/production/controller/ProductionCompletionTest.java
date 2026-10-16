package com.factoryflow.production.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.web.TraceIdFilter;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.domain.StepResult;
import com.factoryflow.production.service.ProductionService;
import com.factoryflow.production.service.WorkOrderService;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.context.WebApplicationContext;

/** 검사 양품 → VIN 발행 → 생산 완료 (docs/specs/production-completion.md). */
@SpringBootTest
@Import(TestContainersConfig.class)
class ProductionCompletionTest {

    static final String WORKER = "3f2a9c1e-0000-4000-8000-000000000001";
    static final String TRACE_ID = "trace-completion-1";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionService productionService;
    @Autowired WorkOrderService workOrderService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 요청 헤더의 traceId 를 이벤트가 이어 쓰는지 보려고 실제 앱처럼 TraceIdFilter 를 건다
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context).addFilters(new TraceIdFilter()).build();
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL");
    }

    @Test
    void inspectionGoodIssuesVinAndCompletesWorkOrderAndProduction() throws Exception {
        long workOrder = workOrderAtInspection(42L, 1L);
        start(workOrder, "INSPECTION", "INSPECTION-01");

        complete(workOrder, "INSPECTION", "{\"result\":\"GOOD\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.vin").value(vinOf(workOrder)));

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT w.status w_status, w.vin, w.completed_at w_done,"
                                + " p.status p_status, p.completed_at p_done, p.id production_id"
                                + " FROM work_order w JOIN production p ON p.id = w.production_id"
                                + " WHERE w.id = ?",
                        workOrder);
        assertThat(row)
                .containsEntry("w_status", "COMPLETED")
                .containsEntry("p_status", "COMPLETED");
        assertThat(row.get("w_done")).isNotNull();
        assertThat(row.get("p_done")).isNotNull();
        String vin = (String) row.get("vin");
        assertThat(vin)
                .hasSize(17)
                .startsWith("KMH00001")
                .endsWith(String.format("%06d", workOrder));

        List<String> completed = outbox("production.completed");
        assertThat(completed).hasSize(1);
        String event = completed.get(0);
        assertThat(JsonPath.<Integer>read(event, "$.payload.orderId")).isEqualTo(42);
        assertThat(((Number) JsonPath.read(event, "$.payload.productionId")).longValue())
                .isEqualTo(((Number) row.get("production_id")).longValue());
        assertThat(JsonPath.<Integer>read(event, "$.payload.productId")).isEqualTo(1);
        assertThat(JsonPath.<String>read(event, "$.payload.vin")).isEqualTo(vin);
        assertThat(JsonPath.<String>read(event, "$.traceId")).isEqualTo(TRACE_ID);
    }

    @Test
    void inspectionGoodReportedByEquipmentAlsoCompletes() {
        long workOrder = workOrderAtInspection(42L, 1L);
        workOrderService.startStep(workOrder, ProductionProcess.INSPECTION, "INSPECTION-02");

        workOrderService.reportStepCompleted(
                workOrder, ProductionProcess.INSPECTION, "INSPECTION-02", StepResult.GOOD, null);

        assertThat(workOrderStatus(workOrder)).isEqualTo("COMPLETED");
        assertThat(productionStatusOf(workOrder)).isEqualTo("COMPLETED");
        assertThat(vinOf(workOrder)).hasSize(17);
        assertThat(outbox("production.completed")).hasSize(1);
    }

    @Test
    void inspectionDefectAndOtherStepsDoNotComplete() throws Exception {
        long workOrder = releasedWorkOrder(42L, 1L);
        start(workOrder, "PRESS", "PRESS-01");
        complete(workOrder, "PRESS", "{\"result\":\"GOOD\"}").andExpect(status().isOk());
        assertThat(workOrderStatus(workOrder)).isEqualTo("IN_PROGRESS");

        long other = workOrderAtInspection(43L, 1L);
        start(other, "INSPECTION", "INSPECTION-01");
        complete(other, "INSPECTION", "{\"result\":\"DEFECT\",\"defectReason\":\"누유\"}")
                .andExpect(status().isOk());

        assertThat(workOrderStatus(other)).isEqualTo("IN_PROGRESS");
        assertThat(productionStatusOf(other)).isEqualTo("IN_PROGRESS");
        assertThat(vinOf(other)).isNull();
        assertThat(outbox("production.completed")).isEmpty();
    }

    @Test
    void productionThatIsNoLongerInProgressCannotComplete() throws Exception {
        long workOrder = workOrderAtInspection(42L, 1L);
        start(workOrder, "INSPECTION", "INSPECTION-01");
        jdbc.update(
                "UPDATE production SET status = 'CANCELLED' WHERE id ="
                        + " (SELECT production_id FROM work_order WHERE id = ?)",
                workOrder);

        complete(workOrder, "INSPECTION", "{\"result\":\"GOOD\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CLOSED"));

        assertThat(workOrderStatus(workOrder)).isEqualTo("IN_PROGRESS");
        assertThat(stepStatus(workOrder, "INSPECTION")).isEqualTo("IN_PROGRESS");
        assertThat(outbox("production.completed")).isEmpty();
    }

    /** 작업지시를 발행하고 검사 앞 공정 4개를 양품으로 끝낸다. */
    private long workOrderAtInspection(long orderId, long productId) {
        long workOrder = releasedWorkOrder(orderId, productId);
        for (ProductionProcess process : ProductionProcess.values()) {
            if (process == ProductionProcess.INSPECTION) {
                break;
            }
            workOrderService.startStep(workOrder, process, process.name() + "-01");
            workOrderService.completeStep(workOrder, process, StepResult.GOOD, null, WORKER);
        }
        return workOrder;
    }

    private long releasedWorkOrder(long orderId, long productId) {
        jdbc.update(
                "INSERT INTO production (order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (?, ?, 1, 'PLANNED', NOW(6), NOW(6))",
                orderId,
                productId);
        long production =
                jdbc.queryForObject(
                        "SELECT id FROM production WHERE order_id = ? AND product_id = ?",
                        Long.class,
                        orderId,
                        productId);
        productionService.markReserved(production);
        return jdbc.queryForObject(
                "SELECT id FROM work_order WHERE production_id = ?", Long.class, production);
    }

    private ResultActions start(long workOrder, String process, String equipmentCode)
            throws Exception {
        return mockMvc.perform(
                post("/production/work-orders/{id}/steps/{process}/start", workOrder, process)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equipmentCode\":\"" + equipmentCode + "\"}"));
    }

    private ResultActions complete(long workOrder, String process, String body) throws Exception {
        return mockMvc.perform(
                post("/production/work-orders/{id}/steps/{process}/complete", workOrder, process)
                        .header("X-User-Id", WORKER)
                        .header("X-Trace-Id", TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private String vinOf(long workOrder) {
        return jdbc.queryForObject(
                "SELECT vin FROM work_order WHERE id = ?", String.class, workOrder);
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

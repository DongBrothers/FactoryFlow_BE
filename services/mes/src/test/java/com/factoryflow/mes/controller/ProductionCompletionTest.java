package com.factoryflow.mes.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.web.TraceIdFilter;
import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.domain.StepResult;
import com.factoryflow.mes.event.listener.payload.ProductionReleased;
import com.factoryflow.mes.service.WorkOrderService;
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

/**
 * 검사 양품 → VIN 발행 → 작업지시 완료 → production 에 완성 알림 (docs/specs/production-completion.md). 생산 건 완료와
 * production.completed 는 production 이 알림을 받아 한다 (production MesEventListenerTest).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
class ProductionCompletionTest {

    static final String WORKER = "3f2a9c1e-0000-4000-8000-000000000001";
    static final String TRACE_ID = "trace-completion-1";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderService workOrderService;

    MockMvc mockMvc;
    long nextProductionId = 900;

    @BeforeEach
    void setUp() {
        // 요청 헤더의 traceId 를 이벤트가 이어 쓰는지 보려고 실제 앱처럼 TraceIdFilter 를 건다
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context).addFilters(new TraceIdFilter()).build();
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL");
    }

    @Test
    void inspectionGoodIssuesVinCompletesTheWorkOrderAndTellsProduction() throws Exception {
        long workOrder = workOrderAtInspection(42L, 1L);
        start(workOrder, "INSPECTION", "INSPECTION-01");

        complete(workOrder, "INSPECTION", "{\"result\":\"GOOD\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.vin").value(vinOf(workOrder)));

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT status w_status, vin, completed_at w_done, production_id"
                                + " FROM work_order WHERE id = ?",
                        workOrder);
        assertThat(row).containsEntry("w_status", "COMPLETED");
        // 완료 시각은 작업지시가 갖는다 (#123)
        assertThat(row.get("w_done")).isNotNull();
        String vin = (String) row.get("vin");
        assertThat(vin)
                .hasSize(17)
                .startsWith("KMH00001")
                .endsWith(String.format("%06d", workOrder));

        List<String> completed = outbox("mes.vehicle-completed");
        assertThat(completed).hasSize(1);
        String event = completed.get(0);
        assertThat(((Number) JsonPath.read(event, "$.payload.productionId")).longValue())
                .isEqualTo(((Number) row.get("production_id")).longValue());
        assertThat(((Number) JsonPath.read(event, "$.payload.workOrderId")).longValue())
                .isEqualTo(workOrder);
        assertThat(JsonPath.<String>read(event, "$.payload.vin")).isEqualTo(vin);
        assertThat(JsonPath.<String>read(event, "$.traceId")).isEqualTo(TRACE_ID);
    }

    @Test
    void inspectionGoodReportedByEquipmentAlsoCompletes() {
        long workOrder = workOrderAtInspection(42L, 1L);
        workOrderService.startStep(workOrder, ProductionProcess.INSPECTION, "INSPECTION-02");

        workOrderService.reportStepCompleted(
                workOrder,
                ProductionProcess.INSPECTION,
                "INSPECTION-02",
                StepResult.GOOD,
                null,
                null);

        assertThat(workOrderStatus(workOrder)).isEqualTo("COMPLETED");
        assertThat(vinOf(workOrder)).hasSize(17);
        assertThat(outbox("mes.vehicle-completed")).hasSize(1);
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
        assertThat(productionStartedSent(other)).isTrue();
        assertThat(vinOf(other)).isNull();
        assertThat(outbox("mes.vehicle-completed")).isEmpty();
    }

    @Test
    void cancelledWorkOrderCannotComplete() throws Exception {
        // 끝난 차는 완성할 수 없다. mes 에서 차가 끝났다는 것은 작업지시 취소다 (생산 건 상태는 production 이 갖는다)
        long workOrder = workOrderAtInspection(42L, 1L);
        start(workOrder, "INSPECTION", "INSPECTION-01");
        jdbc.update("UPDATE work_order SET status = 'CANCELLED' WHERE id = ?", workOrder);

        complete(workOrder, "INSPECTION", "{\"result\":\"GOOD\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CLOSED"));

        assertThat(workOrderStatus(workOrder)).isEqualTo("CANCELLED");
        assertThat(stepStatus(workOrder, "INSPECTION")).isEqualTo("IN_PROGRESS");
        assertThat(outbox("mes.vehicle-completed")).isEmpty();
    }

    @Test
    void vinThatDoesNotFitIsVinRangeExceededAndChangesNothing() throws Exception {
        // 제품 id 가 VIN 의 5자리를 넘는다 (docs/specs/stalled-flows.md "3. VIN 자리 초과")
        long workOrder = workOrderAtInspection(42L, 100_000L);
        start(workOrder, "INSPECTION", "INSPECTION-01");

        complete(workOrder, "INSPECTION", "{\"result\":\"GOOD\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("VIN_RANGE_EXCEEDED"));

        assertThat(workOrderStatus(workOrder)).isEqualTo("IN_PROGRESS");
        assertThat(stepStatus(workOrder, "INSPECTION")).isEqualTo("IN_PROGRESS");
        assertThat(outbox("mes.vehicle-completed")).isEmpty();
    }

    /** 작업지시를 발행하고 검사 앞 공정 4개를 양품으로 끝낸다. */
    private long workOrderAtInspection(long orderId, long productId) {
        long workOrder = releasedWorkOrder(orderId, productId);
        for (ProductionProcess process : ProductionProcess.values()) {
            if (process == ProductionProcess.INSPECTION) {
                break;
            }
            workOrderService.startStep(workOrder, process, process.name() + "-01");
            workOrderService.completeStep(
                    workOrder, process, StepResult.GOOD, null, List.of(), WORKER);
        }
        return workOrder;
    }

    /** production 이 발행한 차(production.released)를 받아 작업지시와 공정 5개를 만든다. */
    private long releasedWorkOrder(long orderId, long productId) {
        long production = nextProductionId++;
        workOrderService.release(new ProductionReleased(production, orderId, productId));
        return jdbc.queryForObject(
                "SELECT id FROM work_order WHERE production_id = ?", Long.class, production);
    }

    private ResultActions start(long workOrder, String process, String equipmentCode)
            throws Exception {
        return mockMvc.perform(
                post("/mes/work-orders/{id}/steps/{process}/start", workOrder, process)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equipmentCode\":\"" + equipmentCode + "\"}"));
    }

    private ResultActions complete(long workOrder, String process, String body) throws Exception {
        return mockMvc.perform(
                post("/mes/work-orders/{id}/steps/{process}/complete", workOrder, process)
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

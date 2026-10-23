package com.factoryflow.mes.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** 의장 공정 LOT 스캔 → 차 완성 이벤트에 실어 보냄 (docs/specs/part-lot-scan.md, #170). */
@SpringBootTest
@Import(TestContainersConfig.class)
class PartLotScanTest {

    static final String WORKER = "3f2a9c1e-0000-4000-8000-000000000001";
    static final String SCANS =
            "[{\"partId\":1001,\"lotNo\":\"LOT-261017-000012\"},"
                    + "{\"partId\":1018,\"lotNo\":\"LOT-INIT-1018\"}]";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderService workOrderService;

    MockMvc mockMvc;
    long nextProductionId = 950;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM work_order_part_scan");
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL");
    }

    @Test
    void assemblyGoodRecordsTheScansWithWorkerAndTime() throws Exception {
        long workOrder = workOrderAtAssembly();
        start(workOrder, "ASSEMBLY", "ASSEMBLY-01");

        complete(workOrder, "ASSEMBLY", "{\"result\":\"GOOD\",\"scannedLots\":" + SCANS + "}")
                .andExpect(status().isOk());

        List<Map<String, Object>> scans = scans(workOrder);
        assertThat(scans).hasSize(2);
        assertThat(scans.get(0))
                .containsEntry("part_id", 1001L)
                .containsEntry("lot_no", "LOT-261017-000012")
                .containsEntry("scanned_by", WORKER);
        assertThat(scans.get(0).get("scanned_at")).isNotNull();
        assertThat(scans.get(1)).containsEntry("part_id", 1018L);
    }

    @Test
    void reworkKeepsOnlyTheScansOfTheLastGoodAssembly() throws Exception {
        long workOrder = workOrderAtAssembly();
        start(workOrder, "ASSEMBLY", "ASSEMBLY-01");
        complete(workOrder, "ASSEMBLY", "{\"result\":\"DEFECT\",\"defectReason\":\"조립 불량\"}")
                .andExpect(status().isOk());
        start(workOrder, "ASSEMBLY", "ASSEMBLY-01");

        complete(
                        workOrder,
                        "ASSEMBLY",
                        "{\"result\":\"GOOD\",\"scannedLots\":"
                                + "[{\"partId\":1001,\"lotNo\":\"LOT-261017-000013\"}]}")
                .andExpect(status().isOk());

        assertThat(scans(workOrder))
                .extracting(row -> row.get("lot_no"))
                .containsExactly("LOT-261017-000013");
    }

    @Test
    void scansOnlyOnAGoodAssembly() throws Exception {
        long workOrder = workOrderAtAssembly();
        start(workOrder, "ASSEMBLY", "ASSEMBLY-01");

        complete(
                        workOrder,
                        "ASSEMBLY",
                        "{\"result\":\"DEFECT\",\"defectReason\":\"x\",\"scannedLots\":"
                                + SCANS
                                + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SCAN_NOT_ALLOWED"));
        assertThat(stepStatus(workOrder, "ASSEMBLY")).isEqualTo("IN_PROGRESS");

        complete(workOrder, "ASSEMBLY", "{\"result\":\"GOOD\"}").andExpect(status().isOk());
        start(workOrder, "INSPECTION", "INSPECTION-01");
        complete(workOrder, "INSPECTION", "{\"result\":\"GOOD\",\"scannedLots\":" + SCANS + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SCAN_NOT_ALLOWED"));
        assertThat(stepStatus(workOrder, "INSPECTION")).isEqualTo("IN_PROGRESS");
        assertThat(scans(workOrder)).isEmpty();
    }

    @Test
    void invalidScansAre400() throws Exception {
        long workOrder = workOrderAtAssembly();
        start(workOrder, "ASSEMBLY", "ASSEMBLY-01");

        for (String scans :
                List.of(
                        "[{\"partId\":1001,\"lotNo\":\"LOT-1\"},{\"partId\":1001,\"lotNo\":\"LOT-2\"}]",
                        "[{\"partId\":1001,\"lotNo\":\"BATCH-1\"}]",
                        "[{\"partId\":1001,\"lotNo\":\"LOT-" + "1".repeat(29) + "\"}]",
                        "[{\"partId\":0,\"lotNo\":\"LOT-1\"}]",
                        "[{\"lotNo\":\"LOT-1\"}]",
                        tooManyScans())) {
            complete(workOrder, "ASSEMBLY", "{\"result\":\"GOOD\",\"scannedLots\":" + scans + "}")
                    .andExpect(status().isBadRequest());
        }
        assertThat(stepStatus(workOrder, "ASSEMBLY")).isEqualTo("IN_PROGRESS");
        assertThat(scans(workOrder)).isEmpty();
    }

    @Test
    void completedVehicleCarriesTheScansToProduction() throws Exception {
        long workOrder = workOrderAtAssembly();
        start(workOrder, "ASSEMBLY", "ASSEMBLY-01");
        complete(workOrder, "ASSEMBLY", "{\"result\":\"GOOD\",\"scannedLots\":" + SCANS + "}");
        start(workOrder, "INSPECTION", "INSPECTION-01");

        complete(workOrder, "INSPECTION", "{\"result\":\"GOOD\"}").andExpect(status().isOk());

        String event = onlyVehicleCompleted();
        assertThat(JsonPath.<List<Map<String, Object>>>read(event, "$.payload.scannedLots"))
                .containsExactly(
                        Map.of("partId", 1001, "lotNo", "LOT-261017-000012"),
                        Map.of("partId", 1018, "lotNo", "LOT-INIT-1018"));
    }

    @Test
    void vehicleWithoutScansSendsAnEmptyList() throws Exception {
        long workOrder = workOrderAtAssembly();
        workOrderService.startStep(workOrder, ProductionProcess.ASSEMBLY, "ASSEMBLY-01");
        workOrderService.completeStep(
                workOrder, ProductionProcess.ASSEMBLY, StepResult.GOOD, null, List.of(), WORKER);
        start(workOrder, "INSPECTION", "INSPECTION-01");

        complete(workOrder, "INSPECTION", "{\"result\":\"GOOD\"}").andExpect(status().isOk());

        assertThat(JsonPath.<List<Object>>read(onlyVehicleCompleted(), "$.payload.scannedLots"))
                .isEmpty();
    }

    @Test
    void scannedLotsAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.CompleteWorkOrderStepRequest.properties.scannedLots")
                                .exists())
                .andExpect(
                        jsonPath("$.components.schemas.ScannedLotRequest.properties.lotNo")
                                .exists());
    }

    private static String tooManyScans() {
        StringBuilder scans = new StringBuilder("[");
        for (int i = 1; i <= 51; i++) {
            scans.append(i > 1 ? "," : "")
                    .append("{\"partId\":")
                    .append(i)
                    .append(",\"lotNo\":\"LOT-")
                    .append(i)
                    .append("\"}");
        }
        return scans.append("]").toString();
    }

    /** 작업지시를 발행하고 의장 앞 공정 3개(로봇)를 양품으로 끝낸다. */
    private long workOrderAtAssembly() {
        long production = nextProductionId++;
        workOrderService.release(new ProductionReleased(production, 42L, 1L));
        long workOrder =
                jdbc.queryForObject(
                        "SELECT id FROM work_order WHERE production_id = ?",
                        Long.class,
                        production);
        for (ProductionProcess process :
                List.of(ProductionProcess.PRESS, ProductionProcess.BODY, ProductionProcess.PAINT)) {
            workOrderService.startStep(workOrder, process, process.name() + "-01");
            workOrderService.completeStep(
                    workOrder, process, StepResult.GOOD, null, List.of(), WORKER);
        }
        return workOrder;
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

    private List<Map<String, Object>> scans(long workOrder) {
        return jdbc.queryForList(
                "SELECT part_id, lot_no, scanned_by, scanned_at FROM work_order_part_scan"
                        + " WHERE work_order_id = ? ORDER BY part_id",
                workOrder);
    }

    private String stepStatus(long workOrder, String process) {
        return jdbc.queryForObject(
                "SELECT status FROM work_order_step WHERE work_order_id = ? AND process = ?",
                String.class,
                workOrder,
                process);
    }

    private String onlyVehicleCompleted() {
        List<String> events =
                jdbc.queryForList(
                        "SELECT payload FROM outbox WHERE event_name = 'mes.vehicle-completed'",
                        String.class);
        assertThat(events).hasSize(1);
        return events.get(0);
    }
}

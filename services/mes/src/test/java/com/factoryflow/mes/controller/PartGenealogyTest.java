package com.factoryflow.mes.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.domain.StepResult;
import com.factoryflow.mes.dto.request.ScannedLotRequest;
import com.factoryflow.mes.event.listener.payload.ProductionReleased;
import com.factoryflow.mes.service.WorkOrderService;
import com.factoryflow.test.container.TestContainersConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 부품 계보(VIN ↔ LOT)는 mes 가 의장에서 찍은 LOT 으로 답한다 (docs/specs/part-genealogy.md, #176). */
@SpringBootTest
@Import(TestContainersConfig.class)
class PartGenealogyTest {

    static final String WORKER = "3f2a9c1e-0000-4000-8000-000000000001";
    static final String ENGINE_LOT = "LOT-261017-000012";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderService workOrderService;

    MockMvc mockMvc;
    long nextProductionId = 970;

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
    void partLotsOfAVehicleAreTheLotsScannedAtAssemblyByPartId() throws Exception {
        long workOrder =
                assembled(
                        new ScannedLotRequest(1018L, "LOT-INIT-1018"),
                        new ScannedLotRequest(1001L, ENGINE_LOT));
        String vin = inspected(workOrder);

        mockMvc.perform(get("/mes/vehicles/{vin}/part-lots", vin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].partId").value(1001))
                .andExpect(jsonPath("$.data[0].lotNo").value(ENGINE_LOT))
                .andExpect(jsonPath("$.data[0].scannedBy").value(WORKER))
                .andExpect(jsonPath("$.data[0].scannedAt").exists())
                .andExpect(jsonPath("$.data[1].partId").value(1018));
    }

    @Test
    void vehicleWithoutScansHasNoPartLotsAndUnknownVinIs404() throws Exception {
        String vin = inspected(assembled());

        mockMvc.perform(get("/mes/vehicles/{vin}/part-lots", vin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/mes/vehicles/{vin}/part-lots", "KMH000019TU999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
        mockMvc.perform(get("/mes/vehicles/{vin}/part-lots", "NOT-A-VIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void vehiclesOfALotIncludeCarsNotYetCompletedInScanOrder() throws Exception {
        // 의장을 지난 차는 아직 검사 전이어도 리콜 대상이다
        long first = assembled(new ScannedLotRequest(1001L, ENGINE_LOT));
        String vin = inspected(first);
        long second = assembled(new ScannedLotRequest(1001L, ENGINE_LOT));
        assembled(new ScannedLotRequest(1001L, "LOT-261017-000013"));

        mockMvc.perform(get("/mes/part-lots/{lotNo}/vehicles", ENGINE_LOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].workOrderId").value(first))
                .andExpect(jsonPath("$.data[0].vin").value(vin))
                .andExpect(jsonPath("$.data[0].productId").value(1))
                .andExpect(jsonPath("$.data[0].partId").value(1001))
                .andExpect(jsonPath("$.data[0].scannedAt").exists())
                .andExpect(jsonPath("$.data[1].workOrderId").value(second))
                .andExpect(jsonPath("$.data[1].vin").isEmpty());
    }

    @Test
    void unknownLotIsEmptyAndBadLotNoIs400() throws Exception {
        mockMvc.perform(get("/mes/part-lots/{lotNo}/vehicles", "LOT-999999-999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/mes/part-lots/{lotNo}/vehicles", "BATCH-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/mes/part-lots/{lotNo}/vehicles", "LOT-" + "1".repeat(29)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void genealogyApisAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/mes/vehicles/{vin}/part-lots'].get.summary").exists())
                .andExpect(
                        jsonPath("$.paths['/mes/part-lots/{lotNo}/vehicles'].get.summary").exists())
                .andExpect(
                        jsonPath("$.paths['/mes/vehicles/{vin}/part-lots'].get.responses['404']")
                                .exists());
    }

    /** 작업지시를 발행하고 의장까지 양품으로 끝낸다. 의장 때 scans 를 찍는다. */
    private long assembled(ScannedLotRequest... scans) {
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
        workOrderService.startStep(workOrder, ProductionProcess.ASSEMBLY, "ASSEMBLY-01");
        workOrderService.completeStep(
                workOrder,
                ProductionProcess.ASSEMBLY,
                StepResult.GOOD,
                null,
                List.of(scans),
                WORKER);
        return workOrder;
    }

    /** 검사를 양품으로 끝내 VIN 을 받는다. */
    private String inspected(long workOrder) {
        workOrderService.startStep(workOrder, ProductionProcess.INSPECTION, "INSPECTION-01");
        workOrderService.completeStep(
                workOrder, ProductionProcess.INSPECTION, StepResult.GOOD, null, List.of(), WORKER);
        return jdbc.queryForObject(
                "SELECT vin FROM work_order WHERE id = ?", String.class, workOrder);
    }
}

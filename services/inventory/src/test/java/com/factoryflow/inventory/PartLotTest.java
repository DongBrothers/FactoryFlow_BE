package com.factoryflow.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.test.container.TestContainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** VIN 으로 부품 LOT, LOT 으로 차 조회 (docs/specs/part-lot.md "3. VIN 조회", "4. LOT 조회"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class PartLotTest {

    static final String VIN_1 = "KMH000017TU000100";
    static final String VIN_2 = "KMH000017TU000101";
    static final String LOT_1 = "LOT-261001-000001";
    static final String LOT_2 = "LOT-261002-000002";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM part_lot_usage");
        jdbc.update("DELETE FROM part_lot");
        jdbc.update("DELETE FROM finished_vehicle");
        jdbc.update("DELETE FROM part");
        long engine = insertPart("ENG-T", "엔진");
        long tire = insertPart("TIRE-T", "타이어");
        long lot1 = insertLot(tire, LOT_1, 1L);
        insertLot(tire, LOT_2, 2L);
        insertFinishedVehicle(VIN_1, 100L, "IN_STOCK");
        insertFinishedVehicle(VIN_2, 101L, "SHIPPED");
        insertFinishedVehicle("KMH000017TU000102", 102L, "IN_STOCK");
        // VIN_1: 타이어 LOT1 2, 타이어 LOT 없음 2, 엔진 LOT 없음 1. VIN_2: 타이어 LOT1 1
        insertUsage(VIN_1, 100L, tire, lot1, 2);
        insertUsage(VIN_1, 100L, tire, null, 2);
        insertUsage(VIN_1, 100L, engine, null, 1);
        insertUsage(VIN_2, 101L, tire, lot1, 1);
    }

    @Test
    void partLotsOfAVehicleAreByPartCodeThenLotWithoutLotFirst() throws Exception {
        mockMvc.perform(get("/inventory/finished-vehicles/{vin}/part-lots", VIN_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].partCode").value("ENG-T"))
                .andExpect(jsonPath("$.data[0].partName").value("엔진"))
                .andExpect(jsonPath("$.data[0].lotNo").doesNotExist())
                .andExpect(jsonPath("$.data[0].qty").value(1))
                .andExpect(jsonPath("$.data[1].partCode").value("TIRE-T"))
                .andExpect(jsonPath("$.data[1].lotNo").doesNotExist())
                .andExpect(jsonPath("$.data[1].qty").value(2))
                .andExpect(jsonPath("$.data[2].lotNo").value(LOT_1))
                .andExpect(jsonPath("$.data[2].qty").value(2))
                .andExpect(jsonPath("$.data[2].receivedAt").exists())
                .andExpect(jsonPath("$.data[2].partId").doesNotExist())
                .andExpect(jsonPath("$.data[2].lotId").doesNotExist())
                .andExpect(jsonPath("$.data[2].productionId").doesNotExist());
    }

    @Test
    void partLotsOfAVehicleAreRejectedOrEmpty() throws Exception {
        mockMvc.perform(get("/inventory/finished-vehicles/{vin}/part-lots", "KMH000017TU00010O"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/inventory/finished-vehicles/{vin}/part-lots", "KMH000017TU000999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FINISHED_VEHICLE_NOT_FOUND"));
        // 이 기능 전에 완성된 차는 사용 기록이 없다
        mockMvc.perform(get("/inventory/finished-vehicles/{vin}/part-lots", "KMH000017TU000102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void vehiclesOfALotAreInUsageOrderWithTheirStatus() throws Exception {
        mockMvc.perform(get("/inventory/part-lots/{lotNo}/vehicles", LOT_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].vin").value(VIN_1))
                .andExpect(jsonPath("$.data[0].productId").value(1))
                .andExpect(jsonPath("$.data[0].status").value("IN_STOCK"))
                .andExpect(jsonPath("$.data[0].partCode").value("TIRE-T"))
                .andExpect(jsonPath("$.data[0].qty").value(2))
                .andExpect(jsonPath("$.data[1].vin").value(VIN_2))
                .andExpect(jsonPath("$.data[1].status").value("SHIPPED"))
                .andExpect(jsonPath("$.data[1].qty").value(1))
                .andExpect(jsonPath("$.data[0].productionId").doesNotExist())
                .andExpect(jsonPath("$.data[0].orderId").doesNotExist());
    }

    @Test
    void vehiclesOfALotAreRejectedOrEmpty() throws Exception {
        mockMvc.perform(get("/inventory/part-lots/{lotNo}/vehicles", "LOT-26101-000001"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/inventory/part-lots/{lotNo}/vehicles", "LOT-261017-999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PART_LOT_NOT_FOUND"));
        mockMvc.perform(get("/inventory/part-lots/{lotNo}/vehicles", LOT_2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void lotApisAreDocumented() throws Exception {
        String byVin = "$.paths['/inventory/finished-vehicles/{vin}/part-lots'].get";
        String byLot = "$.paths['/inventory/part-lots/{lotNo}/vehicles'].get";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byVin + ".summary").value("차에 쓰인 부품 LOT"))
                .andExpect(jsonPath(byVin + ".responses['400']").exists())
                .andExpect(jsonPath(byVin + ".responses['404']").exists())
                .andExpect(jsonPath(byLot + ".summary").value("LOT 이 쓰인 차"))
                .andExpect(jsonPath(byLot + ".responses['400']").exists())
                .andExpect(jsonPath(byLot + ".responses['404']").exists());
    }

    private long insertPart(String code, String name) {
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, reserved, created_at, updated_at)"
                        + " VALUES (?, ?, 'EA', 0, 0, NOW(6), NOW(6))",
                code,
                name);
        return jdbc.queryForObject("SELECT id FROM part WHERE code = ?", Long.class, code);
    }

    private long insertLot(long partId, String lotNo, long purchaseId) {
        jdbc.update(
                "INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty,"
                        + " received_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 10, 7, NOW(6), NOW(6), NOW(6))",
                partId,
                lotNo,
                purchaseId);
        return jdbc.queryForObject("SELECT id FROM part_lot WHERE lot_no = ?", Long.class, lotNo);
    }

    private void insertFinishedVehicle(String vin, long productionId, String status) {
        jdbc.update(
                "INSERT INTO finished_vehicle (vin, production_id, order_id, product_id, status,"
                        + " created_at, updated_at) VALUES (?, ?, 42, 1, ?, NOW(6), NOW(6))",
                vin,
                productionId,
                status);
    }

    private void insertUsage(String vin, long productionId, long partId, Long lotId, int qty) {
        jdbc.update(
                "INSERT INTO part_lot_usage (vin, production_id, part_id, lot_id, qty, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, NOW(6))",
                vin,
                productionId,
                partId,
                lotId,
                qty);
    }
}

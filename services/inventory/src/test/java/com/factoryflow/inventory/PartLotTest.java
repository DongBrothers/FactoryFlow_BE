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

/**
 * inventory 는 LOT 재고 수량만 갖고, 차 ↔ LOT 계보는 mes 가 갖는다 (docs/specs/part-genealogy.md, #176). 계보 조회는 mes
 * PartGenealogyTest 가 본다.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
class PartLotTest {

    static final String VIN = "KMH000017TU000100";
    static final String LOT_1 = "LOT-261001-000001";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;
    long tire;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM part_lot");
        jdbc.update("DELETE FROM finished_vehicle");
        jdbc.update("DELETE FROM part");
        tire = insertPart("TIRE-T", "타이어");
        insertLot(tire, "LOT-INIT-" + tire, null);
        insertLot(tire, LOT_1, 1L);
        insertFinishedVehicle(VIN);
    }

    @Test
    void partLotsOfAVehicleAreNoLongerAnsweredByInventory() throws Exception {
        // 차에 들어간 LOT 은 mes GET /mes/vehicles/{vin}/part-lots 로 본다
        mockMvc.perform(get("/inventory/finished-vehicles/{vin}/part-lots", VIN))
                .andExpect(status().isNotFound());
    }

    @Test
    void vehiclesOfALotAreNoLongerAnsweredByInventory() throws Exception {
        // LOT 이 들어간 차(리콜)는 mes GET /mes/part-lots/{lotNo}/vehicles 로 본다
        mockMvc.perform(get("/inventory/part-lots/{lotNo}/vehicles", LOT_1))
                .andExpect(status().isNotFound());
    }

    @Test
    void lotStockOfAPartStaysInInventory() throws Exception {
        // LOT 별 남은 수량은 재고라 inventory 가 그대로 답한다
        mockMvc.perform(get("/inventory/parts/{partId}/lots", tire))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partCode").value("TIRE-T"))
                .andExpect(jsonPath("$.data.lots.length()").value(2))
                .andExpect(jsonPath("$.data.lots[0].lotNo").value("LOT-INIT-" + tire))
                .andExpect(jsonPath("$.data.lots[1].lotNo").value(LOT_1))
                .andExpect(jsonPath("$.data.lots[1].remainingQty").value(7));
    }

    @Test
    void lotApisInTheDocsAreStockOnly() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/inventory/parts/{partId}/lots'].get").exists())
                .andExpect(
                        jsonPath("$.paths['/inventory/finished-vehicles/{vin}/part-lots']")
                                .doesNotExist())
                .andExpect(
                        jsonPath("$.paths['/inventory/part-lots/{lotNo}/vehicles']")
                                .doesNotExist());
    }

    private long insertPart(String code, String name) {
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, reserved, created_at, updated_at)"
                        + " VALUES (?, ?, 'EA', 0, 0, NOW(6), NOW(6))",
                code,
                name);
        return jdbc.queryForObject("SELECT id FROM part WHERE code = ?", Long.class, code);
    }

    private void insertLot(long partId, String lotNo, Long purchaseId) {
        jdbc.update(
                "INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty,"
                        + " received_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 10, 7, NOW(6), NOW(6), NOW(6))",
                partId,
                lotNo,
                purchaseId);
    }

    private void insertFinishedVehicle(String vin) {
        jdbc.update(
                "INSERT INTO finished_vehicle (vin, production_id, order_id, product_id, status,"
                        + " created_at, updated_at) VALUES (?, 100, 42, 1, 'IN_STOCK', NOW(6), NOW(6))",
                vin);
    }
}

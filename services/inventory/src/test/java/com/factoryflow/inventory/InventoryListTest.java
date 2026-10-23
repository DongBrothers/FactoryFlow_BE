package com.factoryflow.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.test.container.TestContainersConfig;
import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 부품 재고 목록, 완성차 목록, LOT 별 재고 (docs/specs/list-apis.md 3~5). */
@SpringBootTest
@Import(TestContainersConfig.class)
class InventoryListTest {

    static final String VIN_1 = "KMH000017TU000001";
    static final String VIN_2 = "KMH000017TU000002";
    static final String VIN_3 = "KMH000017TU000003";
    static final String VIN_4 = "KMH000017TU000004";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;
    long engine;
    long tire;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM part_lot");
        jdbc.update("DELETE FROM finished_vehicle");
        jdbc.update("DELETE FROM part");
        tire = insertPart("TIRE-T", "타이어", 10, 2);
        engine = insertPart("ENG-T", "엔진", 4, 1);
        long seat = insertPart("SEAT-T", "시트", 3, 0);
        jdbc.update("UPDATE part SET is_deleted = TRUE WHERE id = ?", seat);
        // 오래된 것부터: 1 출고됨, 2 창고, 4 창고. 3 은 없는 VIN (커서로 주면 404)
        insertFinishedVehicle(VIN_1, 1L, "SHIPPED");
        insertFinishedVehicle(VIN_2, 2L, "IN_STOCK");
        insertFinishedVehicle(VIN_4, 4L, "IN_STOCK");
    }

    @Test
    void partsAreListedByCodeWithoutDeletedOnes() throws Exception {
        mockMvc.perform(get("/inventory/parts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].partId").value(engine))
                .andExpect(jsonPath("$.data[0].code").value("ENG-T"))
                .andExpect(jsonPath("$.data[0].name").value("엔진"))
                .andExpect(jsonPath("$.data[0].unit").value("EA"))
                .andExpect(jsonPath("$.data[0].available").value(4))
                .andExpect(jsonPath("$.data[0].reserved").value(1))
                .andExpect(jsonPath("$.data[1].code").value("TIRE-T"));
    }

    @Test
    void finishedVehiclesAreNewestFirstAndTheNextPageContinuesFromTheLastVin() throws Exception {
        mockMvc.perform(get("/inventory/finished-vehicles").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.items[0].vin").value(VIN_4))
                .andExpect(jsonPath("$.data.items[0].productId").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("IN_STOCK"))
                .andExpect(jsonPath("$.data.items[0].completedAt").exists())
                .andExpect(jsonPath("$.data.items[0].productionId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].vin").value(VIN_2))
                .andExpect(jsonPath("$.data.last").value(false))
                .andExpect(jsonPath("$.data.nextVin").value(VIN_2));

        mockMvc.perform(
                        get("/inventory/finished-vehicles")
                                .param("size", "2")
                                .param("lastVin", VIN_2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].vin").value(VIN_1))
                .andExpect(jsonPath("$.data.items[0].shippedAt").exists())
                .andExpect(jsonPath("$.data.last").value(true))
                .andExpect(jsonPath("$.data.nextVin").doesNotExist());
    }

    @Test
    void finishedVehiclesFilterByStatus() throws Exception {
        mockMvc.perform(get("/inventory/finished-vehicles").param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].vin").value(VIN_1));
        mockMvc.perform(
                        get("/inventory/finished-vehicles")
                                .param("status", "IN_STOCK")
                                .param("lastVin", VIN_4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].vin").value(VIN_2));
    }

    @Test
    void finishedVehicleListRejectsBadParameters() throws Exception {
        mockMvc.perform(get("/inventory/finished-vehicles").param("status", "SOLD"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/inventory/finished-vehicles").param("lastVin", "KMH000017TU00000O"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/inventory/finished-vehicles").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/inventory/finished-vehicles").param("lastVin", VIN_3))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FINISHED_VEHICLE_NOT_FOUND"));
    }

    @Test
    void lotsOfAPartAreRemainingOnesInReceiptOrderStartingWithInitialStock() throws Exception {
        // 타이어 실물 12 = 초기 재고 LOT 4 + LOT2 남은 3 + LOT3 남은 5. LOT1 은 다 썼다 (#116, 모든 재고에 LOT)
        insertLot(tire, "LOT-INIT-" + tire, null, 10, 4);
        insertLot(tire, "LOT-261001-000001", 1L, 6, 0);
        insertLot(tire, "LOT-261002-000002", 2L, 6, 3);
        insertLot(tire, "LOT-261003-000003", 3L, 5, 5);

        mockMvc.perform(get("/inventory/parts/{partId}/lots", tire))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partCode").value("TIRE-T"))
                .andExpect(jsonPath("$.data.partName").value("타이어"))
                .andExpect(jsonPath("$.data.onHand").value(12))
                .andExpect(jsonPath("$.data.withoutLotQty").doesNotExist())
                .andExpect(jsonPath("$.data.lots.length()").value(3))
                .andExpect(jsonPath("$.data.lots[0].lotNo").value("LOT-INIT-" + tire))
                .andExpect(jsonPath("$.data.lots[0].remainingQty").value(4))
                .andExpect(jsonPath("$.data.lots[1].lotNo").value("LOT-261002-000002"))
                .andExpect(jsonPath("$.data.lots[1].receivedQty").value(6))
                .andExpect(jsonPath("$.data.lots[1].remainingQty").value(3))
                .andExpect(jsonPath("$.data.lots[1].receivedAt").exists())
                .andExpect(jsonPath("$.data.lots[2].lotNo").value("LOT-261003-000003"))
                .andExpect(jsonPath("$.data.lots[0].lotId").doesNotExist());
    }

    @Test
    void lotsOfAPartRejectBadOrUnknownParts() throws Exception {
        mockMvc.perform(get("/inventory/parts/{partId}/lots", 0))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/inventory/parts/{partId}/lots", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PART_NOT_FOUND"));
        // 처음부터 있던 재고도 초기 재고 LOT 으로 보인다
        insertLot(engine, "LOT-INIT-" + engine, null, 5, 5);
        mockMvc.perform(get("/inventory/parts/{partId}/lots", engine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onHand").value(5))
                .andExpect(jsonPath("$.data.lots.length()").value(1))
                .andExpect(jsonPath("$.data.lots[0].lotNo").value("LOT-INIT-" + engine))
                .andExpect(jsonPath("$.data.lots[0].remainingQty").value(5));
    }

    @Test
    void listApisAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/inventory/parts'].get.summary").value("부품 재고 목록"))
                .andExpect(
                        jsonPath("$.paths['/inventory/finished-vehicles'].get.summary")
                                .value("완성차 목록"))
                .andExpect(
                        jsonPath("$.paths['/inventory/finished-vehicles'].get.responses['404']")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/inventory/parts/{partId}/lots'].get.summary")
                                .value("부품 LOT 별 재고"))
                .andExpect(
                        jsonPath("$.paths['/inventory/parts/{partId}/lots'].get.responses['404']")
                                .exists());
    }

    private long insertPart(String code, String name, int available, int reserved) {
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, reserved, created_at, updated_at)"
                        + " VALUES (?, ?, 'EA', ?, ?, NOW(6), NOW(6))",
                code,
                name,
                available,
                reserved);
        return jdbc.queryForObject("SELECT id FROM part WHERE code = ?", Long.class, code);
    }

    private void insertFinishedVehicle(String vin, long productionId, String status) {
        jdbc.update(
                "INSERT INTO finished_vehicle (vin, production_id, order_id, product_id, status,"
                        + " shipped_at, created_at, updated_at)"
                        + " VALUES (?, ?, 42, 1, ?, ?, NOW(6), NOW(6))",
                vin,
                productionId,
                status,
                "SHIPPED".equals(status) ? Timestamp.valueOf("2026-10-17 10:00:00") : null);
    }

    private void insertLot(
            long partId, String lotNo, Long purchaseId, int received, int remaining) {
        jdbc.update(
                "INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty,"
                        + " received_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6), NOW(6))",
                partId,
                lotNo,
                purchaseId,
                received,
                remaining);
    }
}

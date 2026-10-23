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

/** 부족 부품 조회와 내부 부품 확인 (docs/specs/purchase-manual.md "1", "3"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class PartShortageTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;
    long engine;
    long tire;
    long seat;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM part_reservation");
        jdbc.update("DELETE FROM part");
        engine = insertPart("ENG-T", "엔진", 1);
        tire = insertPart("TIRE-T", "타이어", 3);
        seat = insertPart("SEAT-T", "시트", 10);
    }

    @Test
    void partsWhoseWaitingLinesExceedTheStockAreListedByShortQty() throws Exception {
        // 엔진: 기다림 2 − 재고 1 = 부족 1 / 타이어: 기다림 4 + 5 − 재고 3 = 부족 6 / 시트: 기다림 2 ≤ 재고 10
        insertReservation(100L, engine, 2, "WAITING");
        insertReservation(100L, tire, 4, "WAITING");
        insertReservation(101L, tire, 5, "WAITING");
        insertReservation(102L, tire, 9, "RESERVED");
        insertReservation(100L, seat, 2, "WAITING");

        mockMvc.perform(get("/inventory/parts/shortages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].partId").value(tire))
                .andExpect(jsonPath("$.data[0].code").value("TIRE-T"))
                .andExpect(jsonPath("$.data[0].unit").value("EA"))
                .andExpect(jsonPath("$.data[0].available").value(3))
                .andExpect(jsonPath("$.data[0].waitingQty").value(9))
                .andExpect(jsonPath("$.data[0].shortQty").value(6))
                .andExpect(jsonPath("$.data[0].waitingProductions").value(2))
                .andExpect(jsonPath("$.data[1].partId").value(engine))
                .andExpect(jsonPath("$.data[1].shortQty").value(1));
    }

    @Test
    void deletedPartsAndNoWaitingAreEmpty() throws Exception {
        mockMvc.perform(get("/inventory/parts/shortages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        insertReservation(100L, engine, 5, "WAITING");
        jdbc.update("UPDATE part SET is_deleted = TRUE WHERE id = ?", engine);

        mockMvc.perform(get("/inventory/parts/shortages"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void internalShortageApiReturnsTheSameShortagesForPurchase() throws Exception {
        // purchase 의 발주 제안이 부르는 내부 API (docs/specs/purchase-suggestion.md)
        insertReservation(100L, engine, 2, "WAITING");
        insertReservation(100L, tire, 4, "WAITING");
        insertReservation(101L, tire, 5, "WAITING");

        mockMvc.perform(get("/internal/parts/shortages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].partId").value(tire))
                .andExpect(jsonPath("$.data[0].code").value("TIRE-T"))
                .andExpect(jsonPath("$.data[0].name").value("타이어"))
                .andExpect(jsonPath("$.data[0].unit").value("EA"))
                .andExpect(jsonPath("$.data[0].available").value(3))
                .andExpect(jsonPath("$.data[0].waitingQty").value(9))
                .andExpect(jsonPath("$.data[0].shortQty").value(6))
                .andExpect(jsonPath("$.data[1].partId").value(engine))
                .andExpect(jsonPath("$.data[1].shortQty").value(1));
    }

    @Test
    void internalPartApiReturnsOnlyExistingParts() throws Exception {
        jdbc.update("UPDATE part SET is_deleted = TRUE WHERE id = ?", seat);

        mockMvc.perform(
                        get("/internal/parts")
                                .param("ids", String.valueOf(tire), String.valueOf(seat), "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(tire))
                .andExpect(jsonPath("$.data[0].code").value("TIRE-T"))
                .andExpect(jsonPath("$.data[0].name").value("타이어"));
        mockMvc.perform(get("/internal/parts")).andExpect(status().isBadRequest());
    }

    @Test
    void shortageApiIsDocumentedAndInternalApiIsHidden() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/inventory/parts/shortages'].get.summary")
                                .value("부족 부품"))
                .andExpect(jsonPath("$.paths['/internal/parts']").doesNotExist())
                .andExpect(jsonPath("$.paths['/internal/parts/shortages']").doesNotExist());
    }

    private long insertPart(String code, String name, int available) {
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, reserved, created_at, updated_at)"
                        + " VALUES (?, ?, 'EA', ?, 0, NOW(6), NOW(6))",
                code,
                name,
                available);
        return jdbc.queryForObject("SELECT id FROM part WHERE code = ?", Long.class, code);
    }

    private void insertReservation(long productionId, long partId, int qty, String status) {
        jdbc.update(
                "INSERT INTO part_reservation (production_id, part_id, qty, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                productionId,
                partId,
                qty,
                status);
    }
}

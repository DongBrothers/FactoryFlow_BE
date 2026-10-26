package com.factoryflow.mes.controller;

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
 * 작업자가 공정을 시작할 때 고르는 설비 목록 (docs/specs/production-work-order.md), 설비 상태 이력 조회
 * (docs/specs/equipment-status.md).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
class EquipmentControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update(
                "UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL, is_deleted = FALSE");
        jdbc.update("DELETE FROM equipment_status_history");
    }

    @Test
    void listsEquipmentOfTheProcessWithStatus() throws Exception {
        jdbc.update("UPDATE equipment SET status = 'FAULT' WHERE code = 'BODY-02'");

        mockMvc.perform(get("/mes/equipments").param("process", "BODY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].equipmentCode").value("BODY-01"))
                .andExpect(jsonPath("$.data[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data[1].equipmentCode").value("BODY-02"))
                .andExpect(jsonPath("$.data[1].status").value("FAULT"));
    }

    @Test
    void unknownProcessIs400() throws Exception {
        mockMvc.perform(get("/mes/equipments").param("process", "WELDING"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/mes/equipments")).andExpect(status().isBadRequest());
    }

    // --- 상태 이력 ---

    @Test
    void historyIsNewestFirstAndContinuesWithCursor() throws Exception {
        history("PRESS-01", "RUNNING", "FAULT", "2026-10-24 09:00:00");
        long second = history("PRESS-01", "FAULT", "RUNNING", "2026-10-24 09:10:00");
        history("PRESS-01", "RUNNING", "STOPPED", "2026-10-24 09:20:00");
        history("PRESS-02", "RUNNING", "FAULT", "2026-10-24 09:30:00");

        mockMvc.perform(get("/mes/equipments/PRESS-01/history").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.items[0].fromStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.items[0].toStatus").value("STOPPED"))
                .andExpect(jsonPath("$.data.items[0].changedAt").value("2026-10-24T09:20:00"))
                .andExpect(jsonPath("$.data.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].toStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.last").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(second));

        mockMvc.perform(
                        get("/mes/equipments/PRESS-01/history")
                                .param("lastId", String.valueOf(second))
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].toStatus").value("FAULT"))
                .andExpect(jsonPath("$.data.items[0].changedAt").value("2026-10-24T09:00:00"))
                .andExpect(jsonPath("$.data.last").value(true))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void historyIsEmptyWhenStatusNeverChanged() throws Exception {
        mockMvc.perform(get("/mes/equipments/BODY-01/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.last").value(true));
    }

    @Test
    void historyOfUnknownOrDeletedEquipmentIs404() throws Exception {
        mockMvc.perform(get("/mes/equipments/PRESS-99/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));

        jdbc.update("UPDATE equipment SET is_deleted = TRUE WHERE code = 'PAINT-02'");
        mockMvc.perform(get("/mes/equipments/PAINT-02/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
    }

    @Test
    void historyRejectsMalformedCodeCursorOrSize() throws Exception {
        mockMvc.perform(get("/mes/equipments/press-1/history")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/mes/equipments/PRESS-01/history").param("lastId", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/mes/equipments/PRESS-01/history").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/mes/equipments/PRESS-01/history").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiDocsListHistoryResponseCodes() throws Exception {
        String operation = "$.paths['/mes/equipments/{equipmentCode}/history'].get";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".summary").exists())
                .andExpect(jsonPath(operation + ".responses['200']").exists())
                .andExpect(jsonPath(operation + ".responses['400']").exists())
                .andExpect(jsonPath(operation + ".responses['404']").exists());
    }

    /** 이력 1줄을 넣고 id 를 돌려준다. */
    private long history(String code, String from, String to, String changedAt) {
        jdbc.update(
                "INSERT INTO equipment_status_history"
                        + " (equipment_code, from_status, to_status, changed_at, created_at)"
                        + " VALUES (?, ?, ?, ?, NOW(6))",
                code,
                from,
                to,
                changedAt);
        return jdbc.queryForObject("SELECT MAX(id) FROM equipment_status_history", Long.class);
    }
}

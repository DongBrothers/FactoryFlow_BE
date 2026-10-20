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

/** 작업자가 공정을 시작할 때 고르는 설비 목록 (docs/specs/production-work-order.md). */
@SpringBootTest
@Import(TestContainersConfig.class)
class EquipmentControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL");
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
}

package com.factoryflow.production.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.production.service.ProductionService;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
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

/** 재고 조정으로 찜한 부품을 거둘 때 발행 전 차를 부품 대기로 되돌린다 (docs/specs/stock-adjustment.md "찜한 부품 거둬들이기", #170). */
@SpringBootTest
@Import(TestContainersConfig.class)
class MaterialHoldTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionService productionService;

    MockMvc mockMvc;
    long avante;
    long nextOrderId = 800;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM bom");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update(
                "INSERT INTO product (code, name, created_at, updated_at)"
                        + " VALUES ('AVANTE', '아반떼', NOW(6), NOW(6))");
        avante = jdbc.queryForObject("SELECT id FROM product WHERE code = 'AVANTE'", Long.class);
    }

    @Test
    void holdsUnreleasedReadyCarsInTheGivenOrderUntilEnough() throws Exception {
        long older = insertProduction("READY");
        long released = insertProduction("READY");
        long newer = insertProduction("READY");
        productionService.release(released);
        jdbc.update("DELETE FROM outbox");

        // 후보는 늦게 잡은 예약부터 온다. 발행한 차는 건너뛰고, 합이 3 이상이 되면 멈춘다
        hold(1012L, 3, candidate(newer, 2), candidate(released, 4), candidate(older, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heldProductionIds.length()").value(2))
                .andExpect(jsonPath("$.data.heldProductionIds[0]").value(newer))
                .andExpect(jsonPath("$.data.heldProductionIds[1]").value(older));

        assertThat(statusOf(newer)).isEqualTo("WAITING_MATERIAL");
        assertThat(statusOf(older)).isEqualTo("WAITING_MATERIAL");
        assertThat(statusOf(released)).isEqualTo("READY");
        List<String> changed =
                jdbc.queryForList(
                        "SELECT payload FROM outbox WHERE event_name = 'production.status-changed'",
                        String.class);
        assertThat(changed)
                .extracting(p -> JsonPath.<String>read(p, "$.payload.status"))
                .containsExactly("WAITING_MATERIAL", "WAITING_MATERIAL");
    }

    @Test
    void stopsAtTheFirstCarsThatCoverTheNeed() throws Exception {
        long newer = insertProduction("READY");
        long older = insertProduction("READY");

        hold(1012L, 1, candidate(newer, 4), candidate(older, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heldProductionIds.length()").value(1))
                .andExpect(jsonPath("$.data.heldProductionIds[0]").value(newer));

        assertThat(statusOf(older)).isEqualTo("READY");
    }

    @Test
    void onlyUnreleasedReadyCarsCanBeHeld() throws Exception {
        List<Long> others = new java.util.ArrayList<>();
        for (String status : List.of("PLANNED", "WAITING_MATERIAL", "IN_PROGRESS", "CANCELLED")) {
            others.add(insertProduction(status));
        }
        long ready = insertProduction("READY");

        hold(
                        1012L,
                        1,
                        candidate(others.get(0), 1),
                        candidate(others.get(1), 1),
                        candidate(others.get(2), 1),
                        candidate(others.get(3), 1),
                        candidate(ready, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heldProductionIds.length()").value(1))
                .andExpect(jsonPath("$.data.heldProductionIds[0]").value(ready));
    }

    @Test
    void notEnoughUnreleasedCarsIs409AndChangesNothing() throws Exception {
        long ready = insertProduction("READY");
        long released = insertProduction("READY");
        productionService.release(released);
        jdbc.update("DELETE FROM outbox");

        hold(1012L, 3, candidate(ready, 2), candidate(released, 4))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_UNRELEASED"));

        assertThat(statusOf(ready)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void heldCarCannotBeReleasedUntilItsPartsAreBack() throws Exception {
        long production = insertProduction("READY");
        hold(1012L, 1, candidate(production, 1)).andExpect(status().isOk());

        mockMvc.perform(post("/production/productions/{id}/work-order", production))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCTION_NOT_READY"));

        // 부품이 다시 채워지면 inventory.reserved 로 READY 로 돌아온다 (기존 동작)
        productionService.markReserved(production);
        assertThat(statusOf(production)).isEqualTo("READY");
    }

    @Test
    void invalidRequestIs400() throws Exception {
        long production = insertProduction("READY");

        hold(1012L, 0, candidate(production, 1)).andExpect(status().isBadRequest());
        hold(1012L, 1).andExpect(status().isBadRequest());
        hold(0L, 1, candidate(production, 1)).andExpect(status().isBadRequest());
        hold(1012L, 1, candidate(production, 0)).andExpect(status().isBadRequest());
        assertThat(statusOf(production)).isEqualTo("READY");
    }

    private ResultActions hold(Long partId, int neededQty, String... candidates) throws Exception {
        String body =
                "{\"partId\":%d,\"neededQty\":%d,\"candidates\":[%s]}"
                        .formatted(partId, neededQty, String.join(",", candidates));
        return mockMvc.perform(
                post("/internal/productions/material-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static String candidate(long productionId, int qty) {
        return "{\"productionId\":%d,\"qty\":%d}".formatted(productionId, qty);
    }

    private long insertProduction(String status) {
        long orderId = nextOrderId++;
        jdbc.update(
                "INSERT INTO production (order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (?, ?, 1, ?, NOW(6), NOW(6))",
                orderId,
                avante,
                status);
        return jdbc.queryForObject(
                "SELECT id FROM production WHERE order_id = ?", Long.class, orderId);
    }

    private String statusOf(long production) {
        return jdbc.queryForObject(
                "SELECT status FROM production WHERE id = ?", String.class, production);
    }
}

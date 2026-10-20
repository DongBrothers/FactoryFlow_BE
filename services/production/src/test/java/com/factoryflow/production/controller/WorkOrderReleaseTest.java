package com.factoryflow.production.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.production.scheduler.LineDispatcher;
import com.factoryflow.production.service.ProductionService;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 작업지시 수동 발행과 발행 대기 목록 (docs/specs/work-order-release.md). */
@SpringBootTest
@Import(TestContainersConfig.class)
class WorkOrderReleaseTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionService productionService;
    @Autowired LineDispatcher dispatcher;

    MockMvc mockMvc;
    long avante;
    long nextOrderId = 700;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM production_result");
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM bom");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("UPDATE equipment SET status = 'RUNNING', occupied_step_id = NULL");
        jdbc.update(
                "INSERT INTO product (code, name, created_at, updated_at)"
                        + " VALUES ('AVANTE', '아반떼', NOW(6), NOW(6))");
        avante = jdbc.queryForObject("SELECT id FROM product WHERE code = 'AVANTE'", Long.class);
    }

    @Test
    void reservedProductionWaitsForTheWorkOrder() {
        long production = readyProduction();

        // 부품이 다 잡혀도 작업지시는 생산 담당이 발행할 때까지 없다
        assertThat(statusOf(production)).isEqualTo("READY");
        assertThat(workOrderCount(production)).isZero();
    }

    @Test
    void releaseCreatesTheWorkOrderWithFiveStepsAndSendsNoEvent() throws Exception {
        long production = readyProduction();
        jdbc.update("DELETE FROM outbox");

        String body =
                release(production)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.productionId").value(production))
                        .andExpect(jsonPath("$.data.status").value("RELEASED"))
                        .andExpect(jsonPath("$.data.steps.length()").value(5))
                        .andExpect(jsonPath("$.data.steps[0].process").value("PRESS"))
                        .andExpect(jsonPath("$.data.steps[4].process").value("INSPECTION"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        long workOrder = JsonPath.<Number>read(body, "$.data.workOrderId").longValue();
        assertThat(
                        jdbc.queryForList(
                                "SELECT process FROM work_order_step WHERE work_order_id = ?"
                                        + " ORDER BY seq",
                                String.class,
                                workOrder))
                .containsExactly("PRESS", "BODY", "PAINT", "ASSEMBLY", "INSPECTION");
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT status FROM work_order_step WHERE work_order_id = ?",
                                String.class,
                                workOrder))
                .containsExactly("PENDING");
        assertThat(statusOf(production)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void pressIsDispatchedOnlyAfterTheRelease() throws Exception {
        long production = readyProduction();

        dispatcher.dispatchAll();
        assertThat(outboxCount("production.step-dispatched")).isZero();

        release(production).andExpect(status().isCreated());
        dispatcher.dispatchAll();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT s.status FROM work_order_step s JOIN work_order w"
                                        + " ON w.id = s.work_order_id"
                                        + " WHERE w.production_id = ? AND s.process = 'PRESS'",
                                String.class,
                                production))
                .isEqualTo("DISPATCHED");
        assertThat(outboxCount("production.step-dispatched")).isOne();
    }

    @Test
    void onlyReadyProductionsCanBeReleased() throws Exception {
        for (String status : List.of("PLANNED", "WAITING_MATERIAL", "IN_PROGRESS", "CANCELLED")) {
            long production = insertProduction(status);

            release(production)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRODUCTION_NOT_READY"));
            assertThat(workOrderCount(production)).isZero();
        }
    }

    @Test
    void secondReleaseIs409AndKeepsOneWorkOrder() throws Exception {
        long production = readyProduction();
        release(production).andExpect(status().isCreated());

        release(production)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_RELEASED"));
        assertThat(workOrderCount(production)).isOne();
    }

    @Test
    void unknownProductionIs404AndBadIdIs400() throws Exception {
        release(999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTION_NOT_FOUND"));
        release(0L).andExpect(status().isBadRequest());
    }

    @Test
    void awaitingReleaseListsReadyProductionsWithoutAWorkOrderOldestFirst() throws Exception {
        long first = readyProduction();
        long released = readyProduction();
        long second = readyProduction();
        insertProduction("PLANNED");
        release(released).andExpect(status().isCreated());

        mockMvc.perform(get("/production/productions/awaiting-release").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].productionId").value(first))
                .andExpect(jsonPath("$.data.items[0].productId").value(avante))
                .andExpect(jsonPath("$.data.items[0].productCode").value("AVANTE"))
                .andExpect(jsonPath("$.data.items[0].productName").value("아반떼"))
                .andExpect(jsonPath("$.data.items[0].seq").value(1))
                .andExpect(jsonPath("$.data.last").value(false));
        mockMvc.perform(
                        get("/production/productions/awaiting-release")
                                .param("lastId", String.valueOf(first))
                                .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].productionId").value(second))
                .andExpect(jsonPath("$.data.last").value(true));
        mockMvc.perform(get("/production/productions/awaiting-release").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void releaseApisAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                        "$.paths['/production/productions/{productionId}/work-order'].post.summary")
                                .value("작업지시 발행"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/production/productions/{productionId}/work-order'].post.responses['409']")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/production/productions/awaiting-release'].get.summary")
                                .value("작업지시 발행 대기 목록"));
    }

    /** 생산 건을 만들고 예약 완료(inventory.reserved)를 반영한다. 작업지시는 아직 없다. */
    private long readyProduction() {
        long production = insertProduction("PLANNED");
        productionService.markReserved(production);
        return production;
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

    private ResultActions release(long productionId) throws Exception {
        return mockMvc.perform(post("/production/productions/{id}/work-order", productionId));
    }

    private String statusOf(long production) {
        return jdbc.queryForObject(
                "SELECT status FROM production WHERE id = ?", String.class, production);
    }

    private int workOrderCount(long production) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE production_id = ?",
                Integer.class,
                production);
    }

    private int outboxCount(String eventName) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_name = ?", Integer.class, eventName);
    }
}

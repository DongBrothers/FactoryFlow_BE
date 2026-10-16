package com.factoryflow.purchase.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.purchase.exception.PurchaseErrorCode;
import com.factoryflow.purchase.repository.PurchaseRepository;
import com.factoryflow.purchase.service.PurchaseService;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** 구매 담당 API: 목록, 공급업체, 발주, 입고 (docs/specs/purchase-receiving.md "2. 구매 담당 API"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class PurchaseControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PurchaseService purchaseService;
    @Autowired PurchaseRepository purchaseRepository;

    MockMvc mockMvc;
    long mobis;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM purchase");
        jdbc.update("DELETE FROM outbox");
        mobis = jdbc.queryForObject("SELECT id FROM supplier WHERE name = '현대모비스'", Long.class);
    }

    @Test
    void suppliersAreListed() throws Exception {
        mockMvc.perform(get("/purchases/suppliers"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].name")
                                .value(org.hamcrest.Matchers.hasItems("현대모비스", "한국타이어", "현대트랜시스")));
    }

    @Test
    void orderThenReceivePublishesPurchaseReceived() throws Exception {
        long purchase = requested(1012L, 6);

        order(purchase, mobis)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ORDERED"))
                .andExpect(jsonPath("$.data.supplierId").value(mobis));
        assertThat(row(purchase))
                .containsEntry("status", "ORDERED")
                .containsEntry("supplier_id", mobis);
        assertThat(row(purchase).get("ordered_at")).isNotNull();
        assertThat(outbox()).isEmpty();

        receive(purchase)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));
        assertThat(row(purchase)).containsEntry("status", "RECEIVED");
        assertThat(row(purchase).get("received_at")).isNotNull();
        String event = outbox().get(0);
        assertThat(((Number) JsonPath.read(event, "$.payload.purchaseId")).longValue())
                .isEqualTo(purchase);
        assertThat(JsonPath.<Integer>read(event, "$.payload.partId")).isEqualTo(1012);
        assertThat(JsonPath.<Integer>read(event, "$.payload.qty")).isEqualTo(6);
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void wrongStatusIs409() throws Exception {
        long purchase = requested(1012L, 6);

        receive(purchase)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PURCHASE_STATUS"));
        order(purchase, mobis).andExpect(status().isOk());
        order(purchase, mobis)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PURCHASE_STATUS"));
        receive(purchase).andExpect(status().isOk());
        receive(purchase)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PURCHASE_STATUS"));
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void missingPurchaseOrSupplierIs404() throws Exception {
        long purchase = requested(1012L, 6);

        order(999_999L, mobis)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
        receive(999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
        order(purchase, 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        assertThat(row(purchase)).containsEntry("status", "REQUESTED");
    }

    @Test
    void badRequestsAre400() throws Exception {
        long purchase = requested(1012L, 6);

        mockMvc.perform(
                        post("/purchases/{id}/order", purchase)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        order(purchase, 0L).andExpect(status().isBadRequest());
        order(0L, mobis).andExpect(status().isBadRequest());
        mockMvc.perform(get("/purchases").param("status", "NOPE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/purchases")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/purchases").param("status", "REQUESTED").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void purchasesAreListedByStatusWithCursor() throws Exception {
        long first = requested(1012L, 6);
        long second = requested(1001L, 2);
        long third = requested(1009L, 1);
        order(third, mobis);

        mockMvc.perform(get("/purchases").param("status", "REQUESTED").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.last").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(first))
                .andExpect(jsonPath("$.data.items[0].purchaseId").value(first))
                .andExpect(jsonPath("$.data.items[0].partCode").value("P-1012"))
                .andExpect(jsonPath("$.data.items[0].source").value("AUTO"));
        mockMvc.perform(
                        get("/purchases")
                                .param("status", "REQUESTED")
                                .param("size", "1")
                                .param("lastId", String.valueOf(first)))
                .andExpect(jsonPath("$.data.items[0].purchaseId").value(second))
                .andExpect(jsonPath("$.data.last").value(true));
        mockMvc.perform(get("/purchases").param("status", "ORDERED"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].purchaseId").value(third));
    }

    @Test
    @Transactional
    void concurrentChangeIsPurchaseConflict() {
        // 이 요청이 구매 건을 읽은 뒤 다른 처리(부족 합치기 등)가 먼저 바꿨다
        long purchase = requested(1012L, 6);
        purchaseRepository.findById(purchase);
        jdbc.update("UPDATE purchase SET version = version + 1 WHERE id = ?", purchase);

        assertThatThrownBy(() -> purchaseService.order(purchase, mobis))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(PurchaseErrorCode.PURCHASE_CONFLICT);
    }

    @Test
    void purchaseApiIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/purchases'].get.summary").value("구매 목록"))
                .andExpect(
                        jsonPath("$.paths['/purchases/{purchaseId}/order'].post.responses['409']")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/purchases/{purchaseId}/receive'].post.responses['404']")
                                .exists());
    }

    private long requested(long partId, int qty) {
        jdbc.update(
                "INSERT INTO purchase (part_id, part_code, part_name, qty, status, source,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, 'REQUESTED', 'AUTO', NOW(6), NOW(6))",
                partId,
                "P-" + partId,
                "부품 " + partId,
                qty);
        return jdbc.queryForObject("SELECT MAX(id) FROM purchase", Long.class);
    }

    private ResultActions order(long purchase, long supplierId) throws Exception {
        return mockMvc.perform(
                post("/purchases/{id}/order", purchase)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":" + supplierId + "}"));
    }

    private ResultActions receive(long purchase) throws Exception {
        return mockMvc.perform(post("/purchases/{id}/receive", purchase));
    }

    private Map<String, Object> row(long purchase) {
        return jdbc.queryForMap(
                "SELECT status, supplier_id, ordered_at, received_at FROM purchase WHERE id = ?",
                purchase);
    }

    private List<String> outbox() {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = 'purchase.received' ORDER BY id",
                String.class);
    }
}

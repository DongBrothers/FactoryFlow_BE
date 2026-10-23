package com.factoryflow.purchase.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.purchase.client.inventory.PartClient;
import com.factoryflow.purchase.client.inventory.dto.response.PartShortageResponse;
import com.factoryflow.purchase.exception.PurchaseErrorCode;
import com.factoryflow.test.container.TestContainersConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 발주 제안: 부족 수량 − 발주 중 수량 (docs/specs/purchase-suggestion.md, #170). */
@SpringBootTest
@Import(TestContainersConfig.class)
class PurchaseSuggestionTest {

    static final String WORKER = "6c1e2f3a-0000-0000-0000-000000000001";
    static final long TIRE = 1012L;
    static final long SEAT = 1020L;
    static final long ENGINE = 1001L;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PartClient partClient;

    MockMvc mockMvc;
    long mobis;
    long hankook;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM purchase");
        mobis = supplierId("현대모비스");
        hankook = supplierId("한국타이어");
    }

    @Test
    void suggestsShortMinusOrderedWithTheLastSupplier() throws Exception {
        when(partClient.findShortages())
                .thenReturn(
                        List.of(
                                shortage(TIRE, "TIRE-18", 3, 20),
                                shortage(SEAT, "SEAT-F", 0, 4),
                                shortage(ENGINE, "ENG-G16", 0, 3)));
        // 타이어: 발주 중 6 + 4 = 10, 입고·취소된 건과 다른 부품의 발주는 빼고, 마지막에 산 곳은 한국타이어
        insertPurchase(TIRE, 6, mobis, "ORDERED");
        insertPurchase(TIRE, 4, mobis, "ORDERED");
        insertPurchase(TIRE, 9, mobis, "CANCELLED");
        insertPurchase(TIRE, 5, hankook, "RECEIVED");
        // 시트: 발주 중 6 이 부족 4 보다 많아 더 살 것 없음
        insertPurchase(SEAT, 6, mobis, "ORDERED");

        suggestions()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].partId").value(TIRE))
                .andExpect(jsonPath("$.data[0].partCode").value("TIRE-18"))
                .andExpect(jsonPath("$.data[0].partName").value("TIRE-18 이름"))
                .andExpect(jsonPath("$.data[0].unit").value("EA"))
                .andExpect(jsonPath("$.data[0].available").value(3))
                .andExpect(jsonPath("$.data[0].waitingQty").value(20))
                .andExpect(jsonPath("$.data[0].shortQty").value(17))
                .andExpect(jsonPath("$.data[0].orderedQty").value(10))
                .andExpect(jsonPath("$.data[0].suggestedQty").value(7))
                .andExpect(jsonPath("$.data[0].lastSupplierId").value(hankook))
                .andExpect(jsonPath("$.data[0].lastSupplierName").value("한국타이어"))
                .andExpect(jsonPath("$.data[1].partId").value(ENGINE))
                .andExpect(jsonPath("$.data[1].orderedQty").value(0))
                .andExpect(jsonPath("$.data[1].suggestedQty").value(3))
                .andExpect(jsonPath("$.data[1].lastSupplierId").isEmpty())
                .andExpect(jsonPath("$.data[1].lastSupplierName").isEmpty())
                .andExpect(jsonPath("$.data[2].partId").value(SEAT))
                .andExpect(jsonPath("$.data[2].orderedQty").value(6))
                .andExpect(jsonPath("$.data[2].suggestedQty").value(0))
                .andExpect(jsonPath("$.data[2].lastSupplierName").value("현대모비스"));
    }

    @Test
    void sameSuggestionIsOrderedByPartCode() throws Exception {
        when(partClient.findShortages())
                .thenReturn(
                        List.of(
                                shortage(TIRE, "TIRE-18", 0, 2),
                                shortage(ENGINE, "ENG-G16", 0, 2)));

        suggestions()
                .andExpect(jsonPath("$.data[0].partCode").value("ENG-G16"))
                .andExpect(jsonPath("$.data[1].partCode").value("TIRE-18"));
    }

    @Test
    void lastSupplierIsShownEvenIfDeleted() throws Exception {
        when(partClient.findShortages()).thenReturn(List.of(shortage(TIRE, "TIRE-18", 0, 2)));
        insertPurchase(TIRE, 1, hankook, "RECEIVED");
        jdbc.update("UPDATE supplier SET is_deleted = TRUE WHERE id = ?", hankook);
        try {
            suggestions().andExpect(jsonPath("$.data[0].lastSupplierName").value("한국타이어"));
        } finally {
            jdbc.update("UPDATE supplier SET is_deleted = FALSE WHERE id = ?", hankook);
        }
    }

    @Test
    void noShortageIsAnEmptyList() throws Exception {
        when(partClient.findShortages()).thenReturn(List.of());
        insertPurchase(TIRE, 6, mobis, "ORDERED");

        suggestions().andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void inventoryUnavailableIs503() throws Exception {
        when(partClient.findShortages())
                .thenThrow(new CustomException(PurchaseErrorCode.INVENTORY_UNAVAILABLE));

        suggestions()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_UNAVAILABLE"));
    }

    @Test
    void suggestionApiIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/purchases/suggestions'].get.summary").value("발주 제안"))
                .andExpect(
                        jsonPath("$.paths['/purchases/suggestions'].get.responses['503']")
                                .exists());
    }

    private ResultActions suggestions() throws Exception {
        return mockMvc.perform(get("/purchases/suggestions"));
    }

    private static PartShortageResponse shortage(
            long partId, String code, int available, int waitingQty) {
        return new PartShortageResponse(
                partId, code, code + " 이름", "EA", available, waitingQty, waitingQty - available);
    }

    private void insertPurchase(long partId, int qty, long supplierId, String status) {
        jdbc.update(
                "INSERT INTO purchase (part_id, part_code, part_name, qty, supplier_id, ordered_by,"
                        + " status, ordered_at, created_at, updated_at)"
                        + " VALUES (?, 'P', 'P', ?, ?, ?, ?, NOW(6), NOW(6), NOW(6))",
                partId,
                qty,
                supplierId,
                WORKER,
                status);
    }

    private long supplierId(String name) {
        return jdbc.queryForObject("SELECT id FROM supplier WHERE name = ?", Long.class, name);
    }
}

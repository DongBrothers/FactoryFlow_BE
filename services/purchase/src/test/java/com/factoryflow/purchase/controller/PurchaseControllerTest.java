package com.factoryflow.purchase.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.purchase.client.inventory.PartClient;
import com.factoryflow.purchase.client.inventory.dto.response.PartResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 구매 담당 API: 구매 건 만들기(발주), 입고, 목록, 공급업체 (docs/specs/purchase-manual.md,
 * docs/specs/purchase-receiving.md).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
class PurchaseControllerTest {

    static final String WORKER = "6c1e2f3a-0000-0000-0000-000000000001";
    static final long TIRE = 1012L;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PurchaseService purchaseService;
    @Autowired PurchaseRepository purchaseRepository;
    @MockitoBean PartClient partClient;

    MockMvc mockMvc;
    long mobis;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM purchase");
        jdbc.update("DELETE FROM outbox");
        mobis = jdbc.queryForObject("SELECT id FROM supplier WHERE name = '현대모비스'", Long.class);
        when(partClient.findParts(List.of(TIRE)))
                .thenReturn(List.of(new PartResponse(TIRE, "TIRE-18", "타이어 18인치")));
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
    void createdPurchaseIsOrderedRightAwayWithSnapshotAndWorker() throws Exception {
        create(TIRE, 10, mobis)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ORDERED"))
                .andExpect(jsonPath("$.data.source").doesNotExist())
                .andExpect(jsonPath("$.data.partId").value(TIRE))
                .andExpect(jsonPath("$.data.partCode").value("TIRE-18"))
                .andExpect(jsonPath("$.data.partName").value("타이어 18인치"))
                .andExpect(jsonPath("$.data.qty").value(10))
                .andExpect(jsonPath("$.data.supplierId").value(mobis))
                .andExpect(jsonPath("$.data.orderedBy").value(WORKER))
                .andExpect(jsonPath("$.data.orderedAt").exists());

        long purchase = onlyPurchaseId();
        assertThat(row(purchase))
                .containsEntry("status", "ORDERED")
                .containsEntry("supplier_id", mobis)
                .containsEntry("ordered_by", WORKER);
        assertThat(row(purchase).get("ordered_at")).isNotNull();
        // 만들 때는 이벤트가 없다. 입고 때 purchase.received
        assertThat(outbox()).isEmpty();
    }

    @Test
    void createThenReceivePublishesPurchaseReceived() throws Exception {
        create(TIRE, 6, mobis).andExpect(status().isCreated());
        long purchase = onlyPurchaseId();

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
    void createIsRejectedForUnknownPartSupplierOrUnavailableInventory() throws Exception {
        when(partClient.findParts(List.of(999L))).thenReturn(List.of());
        create(999L, 1, mobis)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PART_NOT_FOUND"));

        create(TIRE, 1, 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));

        when(partClient.findParts(List.of(1001L)))
                .thenThrow(new CustomException(PurchaseErrorCode.INVENTORY_UNAVAILABLE));
        create(1001L, 1, mobis)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_UNAVAILABLE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase", Integer.class)).isZero();
    }

    @Test
    void badCreateRequestsAre400BeforeAskingInventory() throws Exception {
        mockMvc.perform(
                        post("/purchases")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(TIRE, 1, mobis)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/purchases")
                                .header("X-User-Id", WORKER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        for (String worker : new String[] {"", "   ", "x".repeat(37)}) {
            mockMvc.perform(
                            post("/purchases")
                                    .header("X-User-Id", worker)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(TIRE, 1, mobis)))
                    .andExpect(status().isBadRequest());
        }
        create(TIRE, 0, mobis).andExpect(status().isBadRequest());
        create(0L, 1, mobis).andExpect(status().isBadRequest());
        create(TIRE, 1, 0L).andExpect(status().isBadRequest());

        verifyNoInteractions(partClient);
    }

    @Test
    void receiveIsRejectedWhenItCannotBeReceived() throws Exception {
        create(TIRE, 6, mobis);
        long purchase = onlyPurchaseId();
        long cancelled = insertPurchase("CANCELLED");

        receive(purchase).andExpect(status().isOk());
        receive(purchase)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PURCHASE_STATUS"));
        receive(cancelled)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PURCHASE_STATUS"));
        receive(999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
        mockMvc.perform(post("/purchases/{id}/receive", 0)).andExpect(status().isBadRequest());
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void purchasesAreListedByStatusWithCursor() throws Exception {
        long first = insertPurchase("ORDERED");
        long second = insertPurchase("ORDERED");
        long third = insertPurchase("RECEIVED");

        mockMvc.perform(get("/purchases").param("status", "ORDERED").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.last").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(first))
                .andExpect(jsonPath("$.data.items[0].purchaseId").value(first))
                .andExpect(jsonPath("$.data.items[0].partCode").value("TIRE-18"));
        mockMvc.perform(
                        get("/purchases")
                                .param("status", "ORDERED")
                                .param("size", "1")
                                .param("lastId", String.valueOf(first)))
                .andExpect(jsonPath("$.data.items[0].purchaseId").value(second))
                .andExpect(jsonPath("$.data.last").value(true));
        mockMvc.perform(get("/purchases").param("status", "RECEIVED"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].purchaseId").value(third));
    }

    @Test
    void listRejectsBadParametersAndTheRemovedRequestedStatus() throws Exception {
        mockMvc.perform(get("/purchases").param("status", "REQUESTED"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/purchases").param("status", "NOPE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/purchases")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/purchases").param("status", "ORDERED").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void concurrentReceiveIsPurchaseConflict() {
        // 이 요청이 구매 건을 읽은 뒤 다른 입고 요청이 먼저 바꿨다
        long purchase = insertPurchase("ORDERED");
        purchaseRepository.findById(purchase);
        jdbc.update("UPDATE purchase SET version = version + 1 WHERE id = ?", purchase);

        assertThatThrownBy(() -> purchaseService.receive(purchase))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(PurchaseErrorCode.PURCHASE_CONFLICT);
    }

    @Test
    void purchaseApiIsDocumentedWithoutTheRemovedOrderApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/purchases'].get.summary").value("구매 목록"))
                .andExpect(jsonPath("$.paths['/purchases'].post.summary").value("구매 건 만들기 (발주)"))
                .andExpect(jsonPath("$.paths['/purchases'].post.responses['503']").exists())
                .andExpect(jsonPath("$.paths['/purchases/{purchaseId}/order']").doesNotExist())
                .andExpect(
                        jsonPath("$.paths['/purchases/{purchaseId}/receive'].post.responses['404']")
                                .exists());
        verifyNoInteractions(partClient);
    }

    private ResultActions create(long partId, int qty, long supplierId) throws Exception {
        return mockMvc.perform(
                post("/purchases")
                        .header("X-User-Id", WORKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(partId, qty, supplierId)));
    }

    private static String body(long partId, int qty, long supplierId) {
        return "{\"partId\":" + partId + ",\"qty\":" + qty + ",\"supplierId\":" + supplierId + "}";
    }

    private ResultActions receive(long purchase) throws Exception {
        return mockMvc.perform(post("/purchases/{id}/receive", purchase));
    }

    private long insertPurchase(String status) {
        jdbc.update(
                "INSERT INTO purchase (part_id, part_code, part_name, qty, supplier_id, ordered_by,"
                        + " status, ordered_at, created_at, updated_at)"
                        + " VALUES (?, 'TIRE-18', '타이어 18인치', 6, ?, ?, ?, NOW(6), NOW(6), NOW(6))",
                TIRE,
                mobis,
                WORKER,
                status);
        return jdbc.queryForObject("SELECT MAX(id) FROM purchase", Long.class);
    }

    private long onlyPurchaseId() {
        return jdbc.queryForObject("SELECT id FROM purchase", Long.class);
    }

    private Map<String, Object> row(long purchase) {
        return jdbc.queryForMap(
                "SELECT status, supplier_id, ordered_by, ordered_at, received_at FROM purchase"
                        + " WHERE id = ?",
                purchase);
    }

    private List<String> outbox() {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = 'purchase.received' ORDER BY id",
                String.class);
    }
}

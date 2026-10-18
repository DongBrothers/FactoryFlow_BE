package com.factoryflow.production.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.client.inventory.PartClient;
import com.factoryflow.production.client.inventory.dto.response.PartResponse;
import com.factoryflow.production.event.listener.OrderEventListener;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** 제품 + BOM 등록·수정·상세 (docs/specs/master-data.md "3. 제품 + BOM"). inventory 호출(client)만 대신한다. */
@SpringBootTest
@Import(TestContainersConfig.class)
class ProductMasterTest {

    static final String IONIQ5 =
            """
            {"code":"IONIQ5","name":"아이오닉 5",
             "bom":[{"partId":1001,"qty":1},{"partId":1012,"qty":4}]}
            """;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrderEventListener orderEventListener;
    @MockitoBean PartClient partClient;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM bom");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
    }

    @Test
    void registersProductWithBomAndItCanBeOrdered() throws Exception {
        partsExist(1001L, 1012L);

        String body =
                register(IONIQ5)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.code").value("IONIQ5"))
                        .andExpect(jsonPath("$.data.name").value("아이오닉 5"))
                        .andExpect(jsonPath("$.data.bom.length()").value(2))
                        .andExpect(jsonPath("$.data.bom[0].partId").value(1001))
                        .andExpect(jsonPath("$.data.bom[0].qty").value(1))
                        .andExpect(jsonPath("$.data.bom[1].partId").value(1012))
                        .andExpect(jsonPath("$.data.bom[1].qty").value(4))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        long productId = JsonPath.<Number>read(body, "$.data.productId").longValue();
        assertThat(activeBom(productId)).containsExactly(tuple(1001L, 1), tuple(1012L, 4));
        mockMvc.perform(get("/production/products"))
                .andExpect(jsonPath("$.data[0].productId").value(productId));
    }

    @Test
    void duplicateCodeIs409WithoutCallingInventory() throws Exception {
        long deleted = insertProduct("IONIQ5", "아이오닉 5 (단종)");
        jdbc.update("UPDATE product SET is_deleted = TRUE WHERE id = ?", deleted);

        register(IONIQ5)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_DUPLICATED"));
        verifyNoInteractions(partClient);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isOne();
    }

    @Test
    void unknownPartIs404AndNothingIsSaved() throws Exception {
        partsExist(1001L);

        register(IONIQ5)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PART_NOT_FOUND"));
        assertNothingSaved();
    }

    @Test
    void inventoryUnavailableIs503AndNothingIsSaved() throws Exception {
        when(partClient.findParts(anyCollection()))
                .thenThrow(new CustomException(ProductionErrorCode.INVENTORY_UNAVAILABLE));

        register(IONIQ5)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_UNAVAILABLE"));
        assertNothingSaved();
    }

    @Test
    void invalidProductIs400BeforeInventoryIsCalled() throws Exception {
        register("{\"code\":\"IONIQ5\",\"name\":\"아이오닉 5\",\"bom\":[]}")
                .andExpect(status().isBadRequest());
        register("{\"name\":\"아이오닉 5\",\"bom\":[{\"partId\":1001,\"qty\":1}]}")
                .andExpect(status().isBadRequest());
        register("{\"code\":\"IONIQ5\",\"name\":\" \",\"bom\":[{\"partId\":1001,\"qty\":1}]}")
                .andExpect(status().isBadRequest());
        register("{\"code\":\"IONIQ5\",\"name\":\"아이오닉 5\",\"bom\":[{\"partId\":1001,\"qty\":0}]}")
                .andExpect(status().isBadRequest());
        register("{\"code\":\"IONIQ5\",\"name\":\"아이오닉 5\",\"bom\":[{\"qty\":1}]}")
                .andExpect(status().isBadRequest());
        register(
                        "{\"code\":\"IONIQ5\",\"name\":\"아이오닉 5\","
                                + "\"bom\":[{\"partId\":1001,\"qty\":1},{\"partId\":1001,\"qty\":2}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("같은 부품을 BOM 에 두 번 넣을 수 없습니다."));

        verifyNoInteractions(partClient);
        assertNothingSaved();
    }

    @Test
    void updateReplacesTheWholeBom() throws Exception {
        long product = insertProduct("IONIQ5", "아이오닉 5");
        insertBom(product, 1001, 1, false);
        insertBom(product, 1011, 4, true);
        insertBom(product, 1012, 2, false);
        partsExist(1001L, 1011L, 1013L);

        update(
                        product,
                        """
                        {"name":"아이오닉 5 (2027)",
                         "bom":[{"partId":1001,"qty":2},{"partId":1011,"qty":3},{"partId":1013,"qty":1}]}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("IONIQ5"))
                .andExpect(jsonPath("$.data.name").value("아이오닉 5 (2027)"))
                .andExpect(jsonPath("$.data.bom.length()").value(3));

        // 남는 부품은 수량만, 지웠던 부품은 되살리고, 처음 보는 부품은 새 줄, 빠진 부품은 소프트 삭제
        assertThat(activeBom(product))
                .containsExactly(tuple(1001L, 2), tuple(1011L, 3), tuple(1013L, 1));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_deleted FROM bom WHERE product_id = ? AND part_id = 1012",
                                Boolean.class,
                                product))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bom WHERE product_id = ?",
                                Integer.class,
                                product))
                .isEqualTo(4);
    }

    @Test
    void concurrentUpdatesAddingTheSameNewPartBothSucceed() throws Exception {
        // 같은 제품을 동시에 고치면 한 번에 하나씩 처리해, 둘 다 같은 새 부품을 넣어도 BOM 줄은 하나다 (나중에 저장한 쪽이 남음)
        partsExist(1001L, 1013L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                long product = insertProduct("IONIQ5-" + round, "아이오닉 5");
                insertBom(product, 1001, 1, false);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> statuses = new ArrayList<>();
                for (int qty : new int[] {2, 3}) {
                    statuses.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return update(
                                                        product,
                                                        "{\"name\":\"아이오닉 5\",\"bom\":[{\"partId\":1001,\"qty\":1},"
                                                                + "{\"partId\":1013,\"qty\":"
                                                                + qty
                                                                + "}]}")
                                                .andReturn()
                                                .getResponse()
                                                .getStatus();
                                    }));
                }
                start.countDown();
                for (Future<Integer> status : statuses) {
                    assertThat(status.get(10, TimeUnit.SECONDS)).isEqualTo(200);
                }
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM bom WHERE product_id = ? AND part_id = 1013",
                                        Integer.class,
                                        product))
                        .isOne();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void updateOfUnknownOrDeletedProductIs404() throws Exception {
        long deleted = insertProduct("KONA", "코나");
        jdbc.update("UPDATE product SET is_deleted = TRUE WHERE id = ?", deleted);
        String body = "{\"name\":\"코나\",\"bom\":[{\"partId\":1001,\"qty\":1}]}";

        update(deleted, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        update(deleted + 100, body).andExpect(status().isNotFound());
        verifyNoInteractions(partClient);
    }

    @Test
    void detailShowsOnlyTheActiveBomByPart() throws Exception {
        long product = insertProduct("IONIQ5", "아이오닉 5");
        insertBom(product, 1012, 4, false);
        insertBom(product, 1011, 4, true);
        insertBom(product, 1001, 1, false);

        mockMvc.perform(get("/production/products/{id}", product))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(product))
                .andExpect(jsonPath("$.data.bom.length()").value(2))
                .andExpect(jsonPath("$.data.bom[0].partId").value(1001))
                .andExpect(jsonPath("$.data.bom[1].partId").value(1012));
        mockMvc.perform(get("/production/products/{id}", product + 100))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void onlyOrdersAfterTheBomChangeUseTheNewBom() throws Exception {
        long product = insertProduct("IONIQ5", "아이오닉 5");
        insertBom(product, 1001, 1, false);
        orderEventListener.onOrderCreated(orderCreated(10L, product));
        partsExist(1013L);

        update(product, "{\"name\":\"아이오닉 5\",\"bom\":[{\"partId\":1013,\"qty\":2}]}")
                .andExpect(status().isOk());
        orderEventListener.onOrderCreated(orderCreated(11L, product));

        // 바꾸기 전 주문은 그때의 BOM 으로 이미 요청했고, 바꾼 뒤 주문만 새 BOM 으로 요청한다
        assertThat(requestedParts())
                .containsExactly(
                        Map.of("partId", 1001, "qty", 1), Map.of("partId", 1013, "qty", 2));
    }

    @Test
    void productApisAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/production/products'].post.summary").value("제품 등록"))
                .andExpect(
                        jsonPath("$.paths['/production/products'].post.responses['409']").exists())
                .andExpect(
                        jsonPath("$.paths['/production/products'].post.responses['503']").exists())
                .andExpect(
                        jsonPath("$.paths['/production/products/{productId}'].put.summary")
                                .value("제품 수정"))
                .andExpect(
                        jsonPath("$.paths['/production/products/{productId}'].get.summary")
                                .value("제품 상세"));
    }

    private void partsExist(Long... partIds) {
        when(partClient.findParts(anyCollection()))
                .thenReturn(
                        Arrays.stream(partIds)
                                .map(id -> new PartResponse(id, "P-" + id, "부품 " + id))
                                .toList());
    }

    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(
                post("/production/products").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(long productId, String body) throws Exception {
        return mockMvc.perform(
                put("/production/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private List<org.assertj.core.groups.Tuple> activeBom(long productId) {
        return jdbc
                .queryForList(
                        "SELECT part_id, qty_per_unit FROM bom WHERE product_id = ?"
                                + " AND is_deleted = FALSE ORDER BY part_id",
                        productId)
                .stream()
                .map(r -> tuple(((Number) r.get("part_id")).longValue(), r.get("qty_per_unit")))
                .toList();
    }

    private void assertNothingSaved() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bom", Integer.class)).isZero();
    }

    private long insertProduct(String code, String name) {
        jdbc.update(
                "INSERT INTO product (code, name, created_at, updated_at)"
                        + " VALUES (?, ?, NOW(6), NOW(6))",
                code,
                name);
        return jdbc.queryForObject("SELECT MAX(id) FROM product", Long.class);
    }

    private void insertBom(long productId, long partId, int qty, boolean deleted) {
        jdbc.update(
                "INSERT INTO bom (product_id, part_id, qty_per_unit, created_at, updated_at,"
                        + " is_deleted) VALUES (?, ?, ?, NOW(6), NOW(6), ?)",
                productId,
                partId,
                qty,
                deleted);
    }

    private EventEnvelope orderCreated(long orderId, long productId) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "order.created",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of(
                                "orderId",
                                orderId,
                                "products",
                                List.of(Map.of("productId", productId, "qty", 1)))));
    }

    /** material-requested 의 부품들, outbox 순서대로. */
    private List<Map<String, Object>> requestedParts() {
        return jdbc
                .queryForList(
                        "SELECT payload FROM outbox WHERE event_name = 'production.material-requested'"
                                + " ORDER BY id",
                        String.class)
                .stream()
                .flatMap(
                        payload ->
                                JsonPath.<List<Map<String, Object>>>read(payload, "$.payload.parts")
                                        .stream())
                .toList();
    }
}

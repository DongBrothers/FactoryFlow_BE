package com.factoryflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.client.production.ProductClient;
import com.factoryflow.order.client.production.dto.response.ProductResponse;
import com.factoryflow.order.exception.OrderErrorCode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
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

/** 주문 생성 (docs/specs/order-create.md). 실제 MySQL 을 쓰고, production 호출(client)만 대신한다. */
@SpringBootTest
@Import(TestContainersConfig.class)
class OrderCreateTest {

    static final String TWO_PRODUCTS =
            "{\"items\":[{\"productId\":1,\"qty\":2},{\"productId\":2,\"qty\":1}]}";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ProductClient productClient;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM customer_order");
        jdbc.update("DELETE FROM outbox");
    }

    @Test
    void savesOrderItemsAndOutboxTogether() throws Exception {
        productsExist(
                new ProductResponse(1L, "CAR-A", "소형차 A", true),
                new ProductResponse(2L, "CAR-B", "소형차 B", true));

        String body =
                postOrder("7", TWO_PRODUCTS)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.orderId").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String orderNo = JsonPath.read(body, "$.data.orderNo");
        assertThat(orderNo).hasSize(36);

        var order =
                jdbc.queryForMap(
                        "SELECT id, customer_id, status FROM customer_order WHERE order_no = ?",
                        orderNo);
        assertThat(order).containsEntry("customer_id", 7L).containsEntry("status", "RECEIVED");
        long orderId = ((Number) order.get("id")).longValue();

        var items =
                jdbc.queryForList(
                        "SELECT product_id, product_code, product_name, qty, status"
                                + " FROM order_item WHERE order_id = ? ORDER BY product_id",
                        orderId);
        assertThat(items)
                .extracting(
                        row -> row.get("product_code"),
                        row -> row.get("product_name"),
                        row -> row.get("qty"),
                        row -> row.get("status"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CAR-A", "소형차 A", 2, "RECEIVED"),
                        org.assertj.core.groups.Tuple.tuple("CAR-B", "소형차 B", 1, "RECEIVED"));

        var outbox = jdbc.queryForList("SELECT event_name, payload FROM outbox");
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0)).containsEntry("event_name", "order.created");
        String payload = (String) outbox.get(0).get("payload");
        assertThat(((Number) JsonPath.read(payload, "$.payload.orderId")).longValue())
                .isEqualTo(orderId);
        List<Integer> productIds = JsonPath.read(payload, "$.payload.products[*].productId");
        List<Integer> qtys = JsonPath.read(payload, "$.payload.products[*].qty");
        assertThat(productIds).containsExactly(1, 2);
        assertThat(qtys).containsExactly(2, 1);
    }

    @Test
    void unknownProductIs404() throws Exception {
        productsExist(new ProductResponse(1L, "CAR-A", "소형차 A", true));

        postOrder("7", TWO_PRODUCTS)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        assertNothingSaved();
    }

    @Test
    void productWithoutBomIs404() throws Exception {
        productsExist(
                new ProductResponse(1L, "CAR-A", "소형차 A", true),
                new ProductResponse(2L, "CAR-B", "소형차 B", false));

        postOrder("7", TWO_PRODUCTS)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOM_NOT_FOUND"));
        assertNothingSaved();
    }

    @Test
    void productionUnavailableIs503() throws Exception {
        when(productClient.findProducts(anyList()))
                .thenThrow(new CustomException(OrderErrorCode.PRODUCTION_UNAVAILABLE));

        postOrder("7", TWO_PRODUCTS)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCTION_UNAVAILABLE"));
        assertNothingSaved();
    }

    @Test
    void missingCustomerHeaderIs400() throws Exception {
        postOrder(null, TWO_PRODUCTS).andExpect(status().isBadRequest());
        assertRejectedBeforeLookup();
    }

    @Test
    void emptyItemsIs400() throws Exception {
        postOrder("7", "{\"items\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("주문할 제품을 하나 이상 넣어야 합니다."));
        assertRejectedBeforeLookup();
    }

    @Test
    void missingProductIdIs400() throws Exception {
        postOrder("7", "{\"items\":[{\"qty\":2}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("productId 가 필요합니다."));
        assertRejectedBeforeLookup();
    }

    @Test
    void nonPositiveQtyIs400() throws Exception {
        postOrder("7", "{\"items\":[{\"productId\":1,\"qty\":0}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("qty 는 1 이상이어야 합니다."));
        assertRejectedBeforeLookup();
    }

    @Test
    void duplicateProductIs400() throws Exception {
        postOrder("7", "{\"items\":[{\"productId\":1,\"qty\":2},{\"productId\":1,\"qty\":3}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("같은 제품을 두 번 주문할 수 없습니다."));
        assertRejectedBeforeLookup();
    }

    @Test
    void createOrderApiIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/orders'].post.summary").value("주문 생성"))
                .andExpect(jsonPath("$.paths['/orders'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/orders'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/orders'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/orders'].post.responses['503']").exists())
                .andExpect(jsonPath("$.paths['/orders'].post.responses['500']").doesNotExist())
                .andExpect(
                        jsonPath(
                                        "$.paths['/orders'].post.parameters[?(@.name == 'X-User-Id')].description")
                                .value("로그인한 고객 번호 (gateway 가 넣음)"))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.CreateOrderItemRequest.properties.qty.description")
                                .value("주문 수량 (1 이상)"))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.CreateOrderResponse.properties.orderNo.description")
                                .value("주문 번호 (UUID)"));
    }

    private void productsExist(ProductResponse... products) {
        when(productClient.findProducts(anyList())).thenReturn(List.of(products));
    }

    private ResultActions postOrder(String customerId, String body) throws Exception {
        var request = post("/orders").contentType(MediaType.APPLICATION_JSON).content(body);
        if (customerId != null) {
            request.header("X-User-Id", customerId);
        }
        return mockMvc.perform(request);
    }

    private void assertNothingSaved() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_order", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    private void assertRejectedBeforeLookup() {
        verifyNoInteractions(productClient);
        assertNothingSaved();
    }
}

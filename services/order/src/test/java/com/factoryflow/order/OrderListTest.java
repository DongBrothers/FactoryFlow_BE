package com.factoryflow.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.test.container.TestContainersConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 주문 목록 (docs/specs/list-apis.md "2. 주문 목록"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class OrderListTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    /** 넣은 순서 (오래된 것 먼저) */
    List<String> orderNos;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM order_vehicle");
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM customer_order");
        orderNos = new ArrayList<>();
        for (String status :
                List.of("COMPLETED", "IN_PRODUCTION", "COMPLETED", "RECEIVED", "COMPLETED")) {
            orderNos.add(insertOrder(status));
        }
    }

    @Test
    void newestOrdersComeFirstAndTheNextPageContinuesFromTheLastOrderNo() throws Exception {
        mockMvc.perform(get("/orders").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.items[0].orderNo").value(orderNos.get(4)))
                .andExpect(jsonPath("$.data.items[0].customerId").value(7))
                .andExpect(jsonPath("$.data.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].createdAt").exists())
                .andExpect(jsonPath("$.data.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].orderNo").value(orderNos.get(3)))
                .andExpect(jsonPath("$.data.last").value(false))
                .andExpect(jsonPath("$.data.nextOrderNo").value(orderNos.get(3)));

        mockMvc.perform(get("/orders").param("size", "2").param("lastOrderNo", orderNos.get(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].orderNo").value(orderNos.get(0)))
                .andExpect(jsonPath("$.data.last").value(true))
                .andExpect(jsonPath("$.data.nextOrderNo").doesNotExist());
    }

    @Test
    void statusFiltersOrdersAcrossPages() throws Exception {
        mockMvc.perform(get("/orders").param("status", "COMPLETED").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderNo").value(orderNos.get(4)))
                .andExpect(jsonPath("$.data.items[1].orderNo").value(orderNos.get(2)))
                .andExpect(jsonPath("$.data.nextOrderNo").value(orderNos.get(2)));

        // 다른 상태의 주문 번호로 이어 받아도 그보다 오래된 것 중에서 거른다
        mockMvc.perform(
                        get("/orders")
                                .param("status", "COMPLETED")
                                .param("lastOrderNo", orderNos.get(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].orderNo").value(orderNos.get(0)))
                .andExpect(jsonPath("$.data.last").value(true));
    }

    @Test
    void emptyListIsTheLastPage() throws Exception {
        mockMvc.perform(get("/orders").param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0))
                .andExpect(jsonPath("$.data.last").value(true));
    }

    @Test
    void badParametersAre400AndUnknownLastOrderNoIs404() throws Exception {
        mockMvc.perform(get("/orders").param("status", "DONE")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/orders").param("lastOrderNo", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/orders").param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/orders").param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/orders").param("lastOrderNo", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void orderListIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/orders'].get.summary").value("주문 목록"))
                .andExpect(jsonPath("$.paths['/orders'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/orders'].get.responses['404']").exists());
    }

    private String insertOrder(String status) {
        String orderNo = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO customer_order (order_no, customer_id, status, created_at, updated_at)"
                        + " VALUES (?, 7, ?, NOW(6), NOW(6))",
                orderNo,
                status);
        return orderNo;
    }
}

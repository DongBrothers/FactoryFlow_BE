package com.factoryflow.production.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.production.TestContainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestContainersConfig.class)
class InternalProductControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;
    long carA;
    long carB;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM bom");
        jdbc.update("DELETE FROM product");
        carA = insertProduct("CAR-A", "소형차 A");
        carB = insertProduct("CAR-B", "소형차 B");
        jdbc.update(
                "INSERT INTO bom (product_id, part_id, qty_per_unit, created_at, updated_at)"
                        + " VALUES (?, 3, 4, NOW(6), NOW(6))",
                carA);
    }

    @Test
    void returnsExistingProductsWithOrderableByBom() throws Exception {
        mockMvc.perform(
                        get("/internal/products")
                                .param("ids", String.valueOf(carA), String.valueOf(carB), "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.id == " + carA + ")].code").value("CAR-A"))
                .andExpect(jsonPath("$.data[?(@.id == " + carA + ")].name").value("소형차 A"))
                .andExpect(jsonPath("$.data[?(@.id == " + carA + ")].orderable").value(true))
                .andExpect(jsonPath("$.data[?(@.id == " + carB + ")].orderable").value(false));
    }

    @Test
    void deletedProductIsNotReturned() throws Exception {
        jdbc.update("UPDATE product SET is_deleted = TRUE WHERE id = ?", carB);

        mockMvc.perform(get("/internal/products").param("ids", String.valueOf(carB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void deletedBomDoesNotMakeProductOrderable() throws Exception {
        jdbc.update("UPDATE bom SET is_deleted = TRUE WHERE product_id = ?", carA);

        mockMvc.perform(get("/internal/products").param("ids", String.valueOf(carA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderable").value(false));
    }

    @Test
    void missingIdsIs400() throws Exception {
        mockMvc.perform(get("/internal/products")).andExpect(status().isBadRequest());
    }

    @Test
    void internalProductApiIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/internal/products'].get.summary")
                                .value("제품 조회 (서비스 간 내부용)"))
                .andExpect(jsonPath("$.paths['/internal/products'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/internal/products'].get.responses['400']").exists())
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.InternalProductResponse.properties.orderable.description")
                                .value("BOM 이 있어 주문할 수 있는지"));
    }

    private long insertProduct(String code, String name) {
        jdbc.update(
                "INSERT INTO product (code, name, created_at, updated_at)"
                        + " VALUES (?, ?, NOW(6), NOW(6))",
                code,
                name);
        return jdbc.queryForObject("SELECT id FROM product WHERE code = ?", Long.class, code);
    }
}

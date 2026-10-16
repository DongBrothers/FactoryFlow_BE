package com.factoryflow.production.controller;

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

/** 주문할 때 고를 제품 목록 (docs/specs/list-apis.md "1. 제품 목록"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class ProductControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM bom");
        jdbc.update("DELETE FROM product");
    }

    @Test
    void onlyProductsThatCanBeOrderedAreListedByCode() throws Exception {
        long sonata = insertProduct("SONATA", "쏘나타");
        long avante = insertProduct("AVANTE", "아반떼");
        long noBom = insertProduct("CASPER", "캐스퍼");
        long deleted = insertProduct("GRANDEUR", "그랜저");
        long bomDeleted = insertProduct("KONA", "코나");
        insertBom(sonata, false);
        insertBom(avante, false);
        insertBom(deleted, false);
        insertBom(bomDeleted, true);
        jdbc.update("UPDATE product SET is_deleted = TRUE WHERE id = ?", deleted);

        mockMvc.perform(get("/production/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].productId").value(avante))
                .andExpect(jsonPath("$.data[0].code").value("AVANTE"))
                .andExpect(jsonPath("$.data[0].name").value("아반떼"))
                .andExpect(jsonPath("$.data[1].code").value("SONATA"))
                .andExpect(jsonPath("$.data[?(@.productId == " + noBom + ")]").isEmpty());
    }

    @Test
    void noProductIsAnEmptyList() throws Exception {
        mockMvc.perform(get("/production/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void productListIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/production/products'].get.summary").value("제품 목록"))
                .andExpect(
                        jsonPath("$.paths['/production/products'].get.responses['200']").exists());
    }

    private long insertProduct(String code, String name) {
        jdbc.update(
                "INSERT INTO product (code, name, created_at, updated_at)"
                        + " VALUES (?, ?, NOW(6), NOW(6))",
                code,
                name);
        return jdbc.queryForObject("SELECT id FROM product WHERE code = ?", Long.class, code);
    }

    private void insertBom(long productId, boolean deleted) {
        jdbc.update(
                "INSERT INTO bom (product_id, part_id, qty_per_unit, created_at, updated_at,"
                        + " is_deleted) VALUES (?, 3, 4, NOW(6), NOW(6), ?)",
                productId,
                deleted);
    }
}

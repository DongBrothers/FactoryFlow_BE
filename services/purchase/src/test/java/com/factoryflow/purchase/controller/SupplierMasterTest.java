package com.factoryflow.purchase.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
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

/** 공급업체 등록·수정 (docs/specs/master-data.md "2. 공급업체"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class SupplierMasterTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void registersSupplierAndListsIt() throws Exception {
        String body =
                register("{\"name\":\"넥센타이어\",\"contact\":\"02-0000-0004\"}")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.name").value("넥센타이어"))
                        .andExpect(jsonPath("$.data.contact").value("02-0000-0004"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        long supplierId = JsonPath.<Number>read(body, "$.data.supplierId").longValue();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT name, contact, is_deleted FROM supplier WHERE id = ?",
                                supplierId))
                .containsEntry("name", "넥센타이어")
                .containsEntry("contact", "02-0000-0004")
                .containsEntry("is_deleted", false);
        mockMvc.perform(get("/purchases/suppliers"))
                .andExpect(
                        jsonPath("$.data[?(@.supplierId == " + supplierId + ")].name")
                                .value("넥센타이어"));
    }

    @Test
    void updatesSupplier() throws Exception {
        long supplier = insertSupplier("금호타이어");

        update(supplier, "{\"name\":\"금호타이어 (본사)\",\"contact\":\"02-0000-0005\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplierId").value(supplier))
                .andExpect(jsonPath("$.data.name").value("금호타이어 (본사)"))
                .andExpect(jsonPath("$.data.contact").value("02-0000-0005"));

        assertThat(jdbc.queryForMap("SELECT name, contact FROM supplier WHERE id = ?", supplier))
                .containsEntry("name", "금호타이어 (본사)")
                .containsEntry("contact", "02-0000-0005");
    }

    @Test
    void updateOfUnknownOrDeletedSupplierIs404() throws Exception {
        long deleted = insertSupplier("없어진 업체");
        jdbc.update("UPDATE supplier SET is_deleted = TRUE WHERE id = ?", deleted);

        update(deleted, "{\"name\":\"없어진 업체\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        update(deleted + 100, "{\"name\":\"없는 업체\"}").andExpect(status().isNotFound());
    }

    @Test
    void invalidSupplierIs400() throws Exception {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM supplier", Integer.class);

        register("{\"contact\":\"02-0000-0004\"}").andExpect(status().isBadRequest());
        register("{\"name\":\" \"}").andExpect(status().isBadRequest());
        register("{\"name\":\"" + "가".repeat(101) + "\"}").andExpect(status().isBadRequest());
        register("{\"name\":\"넥센타이어\",\"contact\":\"" + "0".repeat(101) + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void supplierApisAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/purchases/suppliers'].post.summary").value("공급업체 등록"))
                .andExpect(
                        jsonPath("$.paths['/purchases/suppliers/{supplierId}'].put.summary")
                                .value("공급업체 수정"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/purchases/suppliers/{supplierId}'].put.responses['404']")
                                .exists());
    }

    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(
                post("/purchases/suppliers").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(long supplierId, String body) throws Exception {
        return mockMvc.perform(
                put("/purchases/suppliers/{id}", supplierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private long insertSupplier(String name) {
        jdbc.update(
                "INSERT INTO supplier (name, created_at, updated_at) VALUES (?, NOW(6), NOW(6))",
                name);
        return jdbc.queryForObject("SELECT MAX(id) FROM supplier", Long.class);
    }
}

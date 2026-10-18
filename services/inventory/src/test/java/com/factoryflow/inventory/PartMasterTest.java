package com.factoryflow.inventory;

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

/** 부품 등록·수정 (docs/specs/master-data.md "1. 부품"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class PartMasterTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM part_lot_usage");
        jdbc.update("DELETE FROM part_lot");
        jdbc.update("DELETE FROM part");
    }

    @Test
    void registersPartWithoutStockOrLot() throws Exception {
        String body =
                register("{\"code\":\"TIRE-19\",\"name\":\"타이어 19인치\",\"unit\":\"EA\"}")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.code").value("TIRE-19"))
                        .andExpect(jsonPath("$.data.name").value("타이어 19인치"))
                        .andExpect(jsonPath("$.data.unit").value("EA"))
                        .andExpect(jsonPath("$.data.available").value(0))
                        .andExpect(jsonPath("$.data.reserved").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        long partId = JsonPath.<Number>read(body, "$.data.partId").longValue();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT code, available, reserved, is_deleted FROM part WHERE id = ?",
                                partId))
                .containsEntry("code", "TIRE-19")
                .containsEntry("available", 0)
                .containsEntry("reserved", 0)
                .containsEntry("is_deleted", false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM part_lot", Integer.class)).isZero();
        mockMvc.perform(get("/inventory/parts"))
                .andExpect(jsonPath("$.data[0].partId").value(partId));
    }

    @Test
    void duplicateCodeIs409EvenForADeletedPart() throws Exception {
        long deleted = insertPart("TIRE-19", 0);
        jdbc.update("UPDATE part SET is_deleted = TRUE WHERE id = ?", deleted);

        register("{\"code\":\"TIRE-19\",\"name\":\"타이어 19인치\",\"unit\":\"EA\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PART_CODE_DUPLICATED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM part", Integer.class)).isOne();
    }

    @Test
    void invalidPartIs400() throws Exception {
        register("{\"name\":\"타이어\",\"unit\":\"EA\"}").andExpect(status().isBadRequest());
        register("{\"code\":\" \",\"name\":\"타이어\",\"unit\":\"EA\"}")
                .andExpect(status().isBadRequest());
        register("{\"code\":\"" + "T".repeat(51) + "\",\"name\":\"타이어\",\"unit\":\"EA\"}")
                .andExpect(status().isBadRequest());
        register("{\"code\":\"TIRE-19\",\"unit\":\"EA\"}").andExpect(status().isBadRequest());
        register("{\"code\":\"TIRE-19\",\"name\":\"" + "가".repeat(101) + "\",\"unit\":\"EA\"}")
                .andExpect(status().isBadRequest());
        register("{\"code\":\"TIRE-19\",\"name\":\"타이어\",\"unit\":\"BOX\"}")
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM part", Integer.class)).isZero();
    }

    @Test
    void updateChangesOnlyNameAndUnit() throws Exception {
        long part = insertPart("TIRE-19", 5);

        update(part, "{\"name\":\"타이어 19인치 (사계절)\",\"unit\":\"SET\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("TIRE-19"))
                .andExpect(jsonPath("$.data.name").value("타이어 19인치 (사계절)"))
                .andExpect(jsonPath("$.data.unit").value("SET"))
                .andExpect(jsonPath("$.data.available").value(5));

        assertThat(
                        jdbc.queryForMap(
                                "SELECT code, name, unit, available FROM part WHERE id = ?", part))
                .containsEntry("code", "TIRE-19")
                .containsEntry("name", "타이어 19인치 (사계절)")
                .containsEntry("unit", "SET")
                .containsEntry("available", 5);
    }

    @Test
    void updateOfUnknownOrDeletedPartIs404() throws Exception {
        long deleted = insertPart("TIRE-19", 0);
        jdbc.update("UPDATE part SET is_deleted = TRUE WHERE id = ?", deleted);

        update(deleted, "{\"name\":\"타이어\",\"unit\":\"EA\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PART_NOT_FOUND"));
        update(deleted + 100, "{\"name\":\"타이어\",\"unit\":\"EA\"}")
                .andExpect(status().isNotFound());
        update(deleted, "{\"name\":\"타이어\",\"unit\":\"BOX\"}").andExpect(status().isBadRequest());
    }

    @Test
    void partApisAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/inventory/parts'].post.summary").value("부품 등록"))
                .andExpect(jsonPath("$.paths['/inventory/parts'].post.responses['409']").exists())
                .andExpect(
                        jsonPath("$.paths['/inventory/parts/{partId}'].put.summary").value("부품 수정"))
                .andExpect(
                        jsonPath("$.paths['/inventory/parts/{partId}'].put.responses['404']")
                                .exists());
    }

    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(
                post("/inventory/parts").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(long partId, String body) throws Exception {
        return mockMvc.perform(
                put("/inventory/parts/{id}", partId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private long insertPart(String code, int available) {
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, created_at, updated_at)"
                        + " VALUES (?, '타이어 19인치', 'EA', ?, NOW(6), NOW(6))",
                code,
                available);
        return jdbc.queryForObject("SELECT id FROM part WHERE code = ?", Long.class, code);
    }
}

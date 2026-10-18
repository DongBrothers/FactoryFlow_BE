package com.factoryflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** 고객 등록과 목록 (docs/specs/customer.md). */
@SpringBootTest
@Import(TestContainersConfig.class)
class CustomerTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM customer");
    }

    @Test
    void registersCustomer() throws Exception {
        String body =
                register("{\"name\":\"현대캐피탈\",\"contact\":\"02-0000-0000\"}")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.name").value("현대캐피탈"))
                        .andExpect(jsonPath("$.data.contact").value("02-0000-0000"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        long customerId = JsonPath.<Number>read(body, "$.data.customerId").longValue();
        assertThat(jdbc.queryForMap("SELECT name, contact FROM customer WHERE id = ?", customerId))
                .containsEntry("name", "현대캐피탈")
                .containsEntry("contact", "02-0000-0000");
    }

    @Test
    void contactIsOptional() throws Exception {
        register("{\"name\":\"현대캐피탈\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contact").doesNotExist());
    }

    @Test
    void invalidCustomerIs400() throws Exception {
        register("{\"contact\":\"02-0000-0000\"}").andExpect(status().isBadRequest());
        register("{\"name\":\"  \"}").andExpect(status().isBadRequest());
        register("{\"name\":\"" + "가".repeat(51) + "\"}").andExpect(status().isBadRequest());
        register("{\"name\":\"현대캐피탈\",\"contact\":\"" + "0".repeat(101) + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer", Integer.class)).isZero();
    }

    @Test
    void customersAreListedInRegistrationOrder() throws Exception {
        register("{\"name\":\"현대캐피탈\",\"contact\":\"02-0000-0000\"}");
        register("{\"name\":\"한국렌터카\"}");

        mockMvc.perform(get("/orders/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("현대캐피탈"))
                .andExpect(jsonPath("$.data[0].contact").value("02-0000-0000"))
                .andExpect(jsonPath("$.data[0].customerId").isNumber())
                .andExpect(jsonPath("$.data[1].name").value("한국렌터카"));
    }

    @Test
    void customerApisAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/orders/customers'].post.summary").value("고객 등록"))
                .andExpect(jsonPath("$.paths['/orders/customers'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/orders/customers'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/orders/customers'].get.summary").value("고객 목록"))
                .andExpect(jsonPath("$.paths['/orders/customers'].get.responses['200']").exists())
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.CreateCustomerRequest.properties.name.description")
                                .value("고객 이름 (50자 이하)"));
    }

    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(
                post("/orders/customers").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}

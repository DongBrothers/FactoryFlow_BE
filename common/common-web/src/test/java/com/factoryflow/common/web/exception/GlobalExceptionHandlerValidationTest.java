package com.factoryflow.common.web.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerValidationTest {

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc =
                MockMvcBuilders.standaloneSetup(new SampleController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setValidator(validator)
                        .build();
    }

    @Test
    void invalidRequestBodyIs400() throws Exception {
        mockMvc.perform(
                        post("/samples")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"qty\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("qty 는 1 이상이어야 합니다."));
    }

    @Test
    void invalidRequestParamIs400() throws Exception {
        mockMvc.perform(get("/samples").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("size 는 1 이상이어야 합니다."));
    }

    @Test
    void invalidPathVariableIs400() throws Exception {
        mockMvc.perform(get("/samples/-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("id 는 1 이상이어야 합니다."));
    }

    @Test
    void missingHeaderIs400() throws Exception {
        mockMvc.perform(get("/samples/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("X-User-Id 헤더가 필요합니다."));
    }

    @Test
    void missingRequestParamIs400() throws Exception {
        mockMvc.perform(get("/samples"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("size 파라미터가 필요합니다."));
    }

    @Test
    void wrongParamTypeIs400() throws Exception {
        mockMvc.perform(get("/samples").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("size 값의 형식이 올바르지 않습니다."));
    }

    @Test
    void malformedJsonBodyIs400() throws Exception {
        mockMvc.perform(
                        post("/samples")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"qty\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다."));
    }

    record SampleRequest(@NotNull @Positive(message = "qty 는 1 이상이어야 합니다.") Integer qty) {}

    @RestController
    static class SampleController {

        @PostMapping("/samples")
        String create(@Valid @RequestBody SampleRequest request) {
            return "ok";
        }

        @GetMapping("/samples")
        String list(@RequestParam("size") @Positive(message = "size 는 1 이상이어야 합니다.") int size) {
            return "ok";
        }

        @GetMapping("/samples/me")
        String me(@RequestHeader("X-User-Id") Long userId) {
            return "ok";
        }

        @GetMapping("/samples/{id}")
        String get(@PathVariable("id") @Positive(message = "id 는 1 이상이어야 합니다.") long id) {
            return "ok";
        }
    }
}

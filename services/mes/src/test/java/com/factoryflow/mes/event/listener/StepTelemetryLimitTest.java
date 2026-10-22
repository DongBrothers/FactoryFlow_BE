package com.factoryflow.mes.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.mes.config.MongoContainerConfig;
import com.factoryflow.mes.domain.StepTelemetry;
import com.factoryflow.mes.event.listener.payload.ProductionReleased;
import com.factoryflow.mes.exception.MesErrorCode;
import com.factoryflow.mes.service.WorkOrderService;
import com.factoryflow.test.container.TestContainersConfig;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** 센서값 메모리 상한 (docs/specs/process-telemetry.md "메모리 상한"). 상한을 작게 줄여 확인한다. */
@SpringBootTest(
        properties = {
            "factoryflow.telemetry.max-samples-per-message=5",
            "factoryflow.telemetry.max-query-samples=3"
        })
@Import({TestContainersConfig.class, MongoContainerConfig.class})
class StepTelemetryLimitTest {

    static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 21, 10, 0);

    @Autowired SimulatorEventListener listener;
    @Autowired WorkOrderService workOrderService;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WebApplicationContext context;

    MockMvc mockMvc;
    long workOrder;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mongoTemplate.dropCollection(StepTelemetry.class);
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM processed_event");
        workOrderService.release(new ProductionReleased(900L, 42L, 1L));
        workOrder =
                jdbc.queryForObject(
                        "SELECT id FROM work_order WHERE production_id = 900", Long.class);
    }

    @Test
    void aMessageOverTheLimitIsNotStored() {
        assertThatThrownBy(() -> listener.onStepTelemetry(telemetry(6)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(MesErrorCode.TELEMETRY_TOO_LARGE);

        assertThat(storedCount()).isZero();
    }

    @Test
    void aMessageAtTheLimitIsStored() {
        listener.onStepTelemetry(telemetry(5));

        assertThat(storedCount()).isEqualTo(5);
    }

    @Test
    void queriesReturnAtMostTheLimitAndSayTheyWereTruncated() throws Exception {
        listener.onStepTelemetry(telemetry(4));

        mockMvc.perform(get("/mes/work-orders/{id}/steps/PRESS/telemetry", workOrder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].samples.length()").value(3))
                .andExpect(jsonPath("$.data.truncated").value(true));
        mockMvc.perform(
                        get("/mes/equipments/PRESS-01/telemetry")
                                .param("from", "2026-10-21T10:00:00")
                                .param("to", "2026-10-21T10:01:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.samples.length()").value(3))
                .andExpect(jsonPath("$.data.samples[0].measuredAt").value("2026-10-21T10:00:00"))
                .andExpect(jsonPath("$.data.truncated").value(true));
    }

    @Test
    void queriesUnderTheLimitAreNotTruncated() throws Exception {
        listener.onStepTelemetry(telemetry(3));

        mockMvc.perform(get("/mes/work-orders/{id}/steps/PRESS/telemetry", workOrder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].samples.length()").value(3))
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    private long storedCount() {
        return mongoTemplate.collectionExists("step_telemetry")
                ? mongoTemplate.count(new Query(), "step_telemetry")
                : 0;
    }

    /** PRESS-01 이 프레스 공정 중 1초 간격으로 잰 값 n 개. */
    private EventEnvelope telemetry(int n) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instant at = BASE.plusSeconds(i).atZone(ZoneId.systemDefault()).toInstant();
            samples.add(Map.of("at", at.toString(), "values", Map.of("pressureTon", 800.0 + i)));
        }
        return new EventEnvelope(
                UUID.randomUUID(),
                "equipment.step-telemetry",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of(
                                "equipmentCode",
                                "PRESS-01",
                                "workOrderId",
                                workOrder,
                                "process",
                                "PRESS",
                                "dispatchSeq",
                                1,
                                "samples",
                                samples)));
    }
}

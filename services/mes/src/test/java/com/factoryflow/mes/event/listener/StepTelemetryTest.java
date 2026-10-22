package com.factoryflow.mes.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.mes.config.MongoContainerConfig;
import com.factoryflow.mes.domain.StepTelemetry;
import com.factoryflow.mes.event.listener.payload.ProductionReleased;
import com.factoryflow.mes.service.WorkOrderService;
import com.factoryflow.test.container.TestContainersConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

/** 로봇 공정 센서값 저장과 조회 (docs/specs/process-telemetry.md). */
@SpringBootTest
@Import({TestContainersConfig.class, MongoContainerConfig.class})
class StepTelemetryTest {

    static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 21, 10, 0);

    @Autowired SimulatorEventListener listener;
    @Autowired WorkOrderService workOrderService;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired WebApplicationContext context;

    MockMvc mockMvc;
    long nextProductionId = 800;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mongoTemplate.dropCollection(StepTelemetry.class);
        jdbc.update("DELETE FROM work_order_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM processed_event");
    }

    @Test
    void telemetryIsStoredOneDocumentPerSampleInATimeSeriesCollection() {
        long workOrder = releasedWorkOrder();

        listener.onStepTelemetry(telemetry(workOrder, "PRESS", "PRESS-01", 1, 0, 5));

        List<Document> docs = mongoTemplate.findAll(Document.class, "step_telemetry");
        assertThat(docs).hasSize(5);
        Document meta = docs.get(0).get("meta", Document.class);
        assertThat(meta.getString("equipmentCode")).isEqualTo("PRESS-01");
        assertThat(meta.getLong("workOrderId")).isEqualTo(workOrder);
        assertThat(meta.getString("process")).isEqualTo("PRESS");
        assertThat(meta.getInteger("dispatchSeq")).isEqualTo(1);
        assertThat(docs.get(0).get("values", Document.class).getDouble("pressureTon"))
                .isEqualTo(800.0);
        // 시계열 컬렉션: 잰 시각·설비 정보로 묶고, 90일 뒤 지운다
        Document options =
                mongoTemplate
                        .getDb()
                        .listCollections()
                        .filter(new Document("name", "step_telemetry"))
                        .first()
                        .get("options", Document.class);
        Document timeseries = options.get("timeseries", Document.class);
        assertThat(timeseries.getString("timeField")).isEqualTo("measuredAt");
        assertThat(timeseries.getString("metaField")).isEqualTo("meta");
        assertThat(((Number) options.get("expireAfterSeconds")).longValue())
                .isEqualTo(Duration.ofDays(90).toSeconds());
    }

    @Test
    void theSameMessageTwiceIsStoredOnce() {
        long workOrder = releasedWorkOrder();
        EventEnvelope event = telemetry(workOrder, "PRESS", "PRESS-01", 1, 0, 3);

        listener.onStepTelemetry(event);
        listener.onStepTelemetry(event);

        assertThat(mongoTemplate.count(new Query(), "step_telemetry")).isEqualTo(3);
    }

    @Test
    void stepTelemetryIsGroupedByDispatchSeqInTimeOrderWithLimits() throws Exception {
        long workOrder = releasedWorkOrder();
        // 회차 2(재작업)를 먼저 받아도 회차 순으로 나온다
        listener.onStepTelemetry(telemetry(workOrder, "PRESS", "PRESS-02", 2, 10, 2));
        listener.onStepTelemetry(telemetry(workOrder, "PRESS", "PRESS-01", 1, 0, 3));

        mockMvc.perform(get("/mes/work-orders/{id}/steps/PRESS/telemetry", workOrder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrderId").value(workOrder))
                .andExpect(jsonPath("$.data.process").value("PRESS"))
                .andExpect(jsonPath("$.data.limits[0].name").value("pressureTon"))
                .andExpect(jsonPath("$.data.limits[0].lower").value(780.0))
                .andExpect(jsonPath("$.data.limits[0].upper").value(820.0))
                .andExpect(jsonPath("$.data.runs.length()").value(2))
                .andExpect(jsonPath("$.data.runs[0].dispatchSeq").value(1))
                .andExpect(jsonPath("$.data.runs[0].equipmentCode").value("PRESS-01"))
                .andExpect(jsonPath("$.data.runs[0].samples.length()").value(3))
                .andExpect(
                        jsonPath("$.data.runs[0].samples[0].measuredAt")
                                .value("2026-10-21T10:00:00"))
                .andExpect(jsonPath("$.data.runs[0].samples[2].values.pressureTon").value(802.0))
                .andExpect(jsonPath("$.data.runs[1].dispatchSeq").value(2))
                .andExpect(jsonPath("$.data.runs[1].equipmentCode").value("PRESS-02"));
    }

    @Test
    void stepTelemetryRejectsHumanStepsAndUnknownWorkOrders() throws Exception {
        long workOrder = releasedWorkOrder();

        mockMvc.perform(get("/mes/work-orders/{id}/steps/PAINT/telemetry", workOrder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs.length()").value(0));
        mockMvc.perform(get("/mes/work-orders/{id}/steps/ASSEMBLY/telemetry", workOrder))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/mes/work-orders/{id}/steps/WELDING/telemetry", workOrder))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/mes/work-orders/{id}/steps/PRESS/telemetry", 0))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/mes/work-orders/{id}/steps/PRESS/telemetry", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
    }

    @Test
    void equipmentTelemetryReturnsSamplesInTheRangeInTimeOrder() throws Exception {
        long first = releasedWorkOrder();
        long second = releasedWorkOrder();
        listener.onStepTelemetry(telemetry(first, "PRESS", "PRESS-01", 1, 0, 3));
        listener.onStepTelemetry(telemetry(second, "PRESS", "PRESS-01", 1, 60, 2));
        listener.onStepTelemetry(telemetry(second, "BODY", "BODY-01", 1, 0, 2));

        // [10:00:01, 10:01:00) → 첫 차의 두·세 번째 값만 (두 번째 차는 10:01:00 부터)
        mockMvc.perform(
                        get("/mes/equipments/PRESS-01/telemetry")
                                .param("from", "2026-10-21T10:00:01")
                                .param("to", "2026-10-21T10:01:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.equipmentCode").value("PRESS-01"))
                .andExpect(jsonPath("$.data.limits.length()").value(2))
                .andExpect(jsonPath("$.data.samples.length()").value(2))
                .andExpect(jsonPath("$.data.samples[0].measuredAt").value("2026-10-21T10:00:01"))
                .andExpect(jsonPath("$.data.samples[0].workOrderId").value(first))
                .andExpect(jsonPath("$.data.samples[0].dispatchSeq").value(1));
    }

    @Test
    void equipmentTelemetryRejectsBadRangesAndUnknownEquipment() throws Exception {
        String path = "/mes/equipments/{code}/telemetry";
        mockMvc.perform(
                        get(path, "PRESS-01")
                                .param("from", "2026-10-21T10:00:00")
                                .param("to", "2026-10-21T11:00:01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        get(path, "PRESS-01")
                                .param("from", "2026-10-21T10:00:00")
                                .param("to", "2026-10-21T10:00:00"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        get(path, "PRESS-01")
                                .param("from", "yesterday")
                                .param("to", "2026-10-21T10:00:00"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(path, "PRESS-01").param("to", "2026-10-21T10:00:00"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        get(path, "press_1")
                                .param("from", "2026-10-21T10:00:00")
                                .param("to", "2026-10-21T10:30:00"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        get(path, "PRESS-99")
                                .param("from", "2026-10-21T10:00:00")
                                .param("to", "2026-10-21T10:30:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        // 정확히 1시간은 된다
        mockMvc.perform(
                        get(path, "PRESS-01")
                                .param("from", "2026-10-21T10:00:00")
                                .param("to", "2026-10-21T11:00:00"))
                .andExpect(status().isOk());
    }

    @Test
    void telemetryApisAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                        "$.paths['/mes/work-orders/{workOrderId}/steps/{process}/telemetry'].get.summary")
                                .value("공정 센서값"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/mes/work-orders/{workOrderId}/steps/{process}/telemetry'].get.responses['404']")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/mes/equipments/{equipmentCode}/telemetry'].get.summary")
                                .value("설비 센서값"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/mes/equipments/{equipmentCode}/telemetry'].get.responses['400']")
                                .exists());
    }

    @Test
    void receivesTelemetryFromRabbitMq() {
        long workOrder = releasedWorkOrder();

        send(telemetry(workOrder, "PAINT", "PAINT-01", 1, 0, 4));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                mongoTemplate.collectionExists("step_telemetry")
                                                        ? mongoTemplate.findAll(
                                                                Document.class, "step_telemetry")
                                                        : List.of())
                                        .hasSize(4));
    }

    private long releasedWorkOrder() {
        long production = nextProductionId++;
        workOrderService.release(new ProductionReleased(production, 42L, 1L));
        return jdbc.queryForObject(
                "SELECT id FROM work_order WHERE production_id = ?", Long.class, production);
    }

    /**
     * 설비가 공정 하나를 하며 잰 값 n 개. 첫 값은 BASE + startSecond 초, 1초 간격. 값은 공정 항목의 하한 근처에서 1씩 오른다 (PRESS 압력은
     * 800, 801, …).
     */
    private EventEnvelope telemetry(
            long workOrder,
            String process,
            String equipment,
            int dispatchSeq,
            int startSecond,
            int n) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instant at =
                    BASE.plusSeconds(startSecond + i).atZone(ZoneId.systemDefault()).toInstant();
            samples.add(Map.of("at", at.toString(), "values", valuesOf(process, i)));
        }
        Map<String, Object> payload =
                Map.of(
                        "equipmentCode", equipment,
                        "workOrderId", workOrder,
                        "process", process,
                        "dispatchSeq", dispatchSeq,
                        "samples", samples);
        return new EventEnvelope(
                UUID.randomUUID(),
                "equipment.step-telemetry",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(payload));
    }

    private static Map<String, Double> valuesOf(String process, int i) {
        return switch (process) {
            case "PRESS" -> Map.of("pressureTon", 800.0 + i, "strokeMm", 300.0);
            case "BODY" -> Map.of("weldCurrentKa", 10.0, "weldVoltageV", 24.0);
            default ->
                    Map.of("boothTempC", 23.0, "humidityPct", 60.0, "filmThicknessUm", 110.0 + i);
        };
    }

    private void send(EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                event.eventName(),
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }
}

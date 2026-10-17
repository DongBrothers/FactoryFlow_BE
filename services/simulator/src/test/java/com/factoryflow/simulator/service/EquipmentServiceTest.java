package com.factoryflow.simulator.service;

import static com.factoryflow.simulator.SimulatorTestSupport.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.simulator.SimulatorTestSupport;
import com.factoryflow.simulator.domain.EquipmentStatus;
import com.factoryflow.simulator.event.publisher.EquipmentEventPublisher;
import com.factoryflow.test.container.TestContainersConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** 가상 설비: 배정 → 시작·완료 보고, 중복·무시, 고장·정지, 내부 API (docs/specs/simulator.md). */
@SpringBootTest(
        properties = {
            "factoryflow.simulator.start-delay=100ms",
            "factoryflow.simulator.work-time.PRESS=300ms",
            "factoryflow.simulator.defect-rate=0",
            "factoryflow.simulator.fault-rate=0",
            "factoryflow.simulator.resend-interval=200ms"
        })
@Import(TestContainersConfig.class)
class EquipmentServiceTest {

    @Autowired EquipmentService equipmentService;
    @Autowired EquipmentEventPublisher publisher;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired WebApplicationContext context;

    SimulatorTestSupport support;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        support = new SimulatorTestSupport(rabbitTemplate, objectMapper, amqpAdmin);
        for (String code : new String[] {"PRESS-01", "PRESS-02"}) {
            equipmentService.changeStatus(code, EquipmentStatus.RUNNING);
        }
        // 되돌리며 보낸 상태 이벤트가 브로커에 다 들어간 뒤(확인 받음) 큐를 비운다
        await().atMost(Duration.ofSeconds(5)).until(() -> publisher.pendingCount() == 0);
        support.purge(amqpAdmin);
    }

    @Test
    void dispatchIsReportedStartedThenCompletedWithTheReceivedTraceId() {
        support.send(support.dispatch(UUID.randomUUID(), "trace-sim-1", 12L, "PRESS", "PRESS-01"));

        String started = support.next(5000);
        assertThat(field(started, "$.eventName")).isEqualTo("equipment.step-started");
        assertThat(field(started, "$.traceId")).isEqualTo("trace-sim-1");
        assertThat(field(started, "$.version")).isEqualTo("1");
        assertThat(UUID.fromString(field(started, "$.eventId"))).isNotNull();
        assertThat(field(started, "$.occurredAt")).isNotBlank();
        assertThat(field(started, "$.payload.equipmentCode")).isEqualTo("PRESS-01");
        assertThat(field(started, "$.payload.workOrderId")).isEqualTo("12");
        assertThat(field(started, "$.payload.process")).isEqualTo("PRESS");

        String completed = support.next(5000);
        assertThat(field(completed, "$.eventName")).isEqualTo("equipment.step-completed");
        assertThat(field(completed, "$.traceId")).isEqualTo("trace-sim-1");
        assertThat(field(completed, "$.payload.result")).isEqualTo("GOOD");
        assertThat(field(completed, "$.payload.defectReason")).isNull();
        // 설비가 다시 비고, 보낸 메시지는 모두 브로커 확인을 받았다
        assertThat(equipmentService.findEquipments())
                .filteredOn(e -> e.equipmentCode().equals("PRESS-01"))
                .singleElement()
                .satisfies(e -> assertThat(e.workOrderId()).isNull());
        await().atMost(Duration.ofSeconds(5)).until(() -> publisher.pendingCount() == 0);
    }

    @Test
    void reportsCarryTheDispatchSeqOfTheDispatch() {
        // 배정 회차 번호를 보고에 그대로 돌려준다 (docs/specs/simulator-restart.md)
        support.send(
                support.dispatch(UUID.randomUUID(), "trace-sim-seq", 14L, "PRESS", "PRESS-01", 3));

        assertThat(field(support.next(5000), "$.payload.dispatchSeq")).isEqualTo("3");
        assertThat(field(support.next(5000), "$.payload.dispatchSeq")).isEqualTo("3");
    }

    @Test
    void announceSendsTheStatusOfEveryEquipment() {
        // 시작할 때 보내는 알림: 설비가 기준이라 production 이 고장으로 알던 설비도 지금 상태로 맞춘다
        equipmentService.changeStatus("PRESS-02", EquipmentStatus.FAULT);
        assertThat(field(support.next(5000), "$.payload.status")).isEqualTo("FAULT");

        equipmentService.announceStatuses();

        List<String> statuses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String message = support.next(5000);
            assertThat(field(message, "$.eventName")).isEqualTo("equipment.status-changed");
            statuses.add(
                    field(message, "$.payload.equipmentCode")
                            + "="
                            + field(message, "$.payload.status"));
        }
        assertThat(statuses).hasSize(10).contains("PRESS-01=RUNNING", "PRESS-02=FAULT");
        assertThat(support.next(500)).isNull();
    }

    @Test
    void sameDispatchTwiceIsWorkedOnce() {
        Map<String, Object> dispatch =
                support.dispatch(UUID.randomUUID(), "trace-sim-2", 13L, "PRESS", "PRESS-01");

        support.send(dispatch);
        support.send(dispatch);

        assertThat(field(support.next(5000), "$.eventName")).isEqualTo("equipment.step-started");
        assertThat(field(support.next(5000), "$.eventName")).isEqualTo("equipment.step-completed");
        assertThat(support.next(1000)).isNull();
    }

    @Test
    void stoppedBusyOrUnknownEquipmentIgnoresTheDispatch() {
        equipmentService.changeStatus("PRESS-02", EquipmentStatus.STOPPED);
        assertThat(field(support.next(5000), "$.payload.status")).isEqualTo("STOPPED");

        support.send(support.dispatch(UUID.randomUUID(), "t", 14L, "PRESS", "PRESS-02"));
        support.send(support.dispatch(UUID.randomUUID(), "t", 15L, "PRESS", "NOPE-99"));
        support.send(support.dispatch(UUID.randomUUID(), "t", 16L, "PRESS", "PRESS-01"));
        support.send(support.dispatch(UUID.randomUUID(), "t", 17L, "PRESS", "PRESS-01"));

        // 연달아 보낸 메시지도 다른 채널로 나가면 브로커 도착 순서가 바뀔 수 있다. 16·17 중 먼저 온 것 하나만 받고, 다른 하나는 작업 중이라 무시한다
        String started = support.next(5000);
        assertThat(field(started, "$.eventName")).isEqualTo("equipment.step-started");
        String taken = field(started, "$.payload.workOrderId");
        assertThat(taken).isIn("16", "17");
        String completed = support.next(5000);
        assertThat(field(completed, "$.eventName")).isEqualTo("equipment.step-completed");
        assertThat(field(completed, "$.payload.workOrderId")).isEqualTo(taken);
        // 정지 설비(14), 없는 설비(15), 작업 중이던 때 온 다른 하나는 보고가 없다
        assertThat(support.next(1000)).isNull();
    }

    @Test
    void manualProcessEquipmentDoesNotTakeADispatch() {
        // 의장·검사는 사람 공정이라 설비가 배정을 받지 않는다 (작업자 API 로 진행)
        support.send(support.dispatch(UUID.randomUUID(), "t", 19L, "ASSEMBLY", "ASSEMBLY-01"));

        assertThat(support.next(1500)).isNull();
        assertThat(equipmentService.findEquipments())
                .filteredOn(e -> e.equipmentCode().equals("ASSEMBLY-01"))
                .singleElement()
                .satisfies(e -> assertThat(e.workOrderId()).isNull());
    }

    @Test
    void faultDuringWorkDropsTheJob() throws Exception {
        support.send(support.dispatch(UUID.randomUUID(), "t", 18L, "PRESS", "PRESS-01"));
        assertThat(field(support.next(5000), "$.eventName")).isEqualTo("equipment.step-started");

        mockMvc.perform(post("/internal/simulator/equipments/PRESS-01/fault"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAULT"))
                .andExpect(jsonPath("$.data.workOrderId").doesNotExist());

        String changed = support.next(5000);
        assertThat(field(changed, "$.eventName")).isEqualTo("equipment.status-changed");
        assertThat(field(changed, "$.payload.equipmentCode")).isEqualTo("PRESS-01");
        assertThat(field(changed, "$.payload.process")).isEqualTo("PRESS");
        assertThat(field(changed, "$.payload.status")).isEqualTo("FAULT");
        // 버린 작업의 완료 보고는 나가지 않는다
        assertThat(support.next(1000)).isNull();
    }

    @Test
    void internalApiShowsAndChangesEquipment() throws Exception {
        mockMvc.perform(get("/internal/simulator/equipments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[0].equipmentCode").value("PRESS-01"));

        mockMvc.perform(post("/internal/simulator/equipments/PRESS-02/run"))
                .andExpect(status().isOk());
        assertThat(support.next(500)).isNull();

        mockMvc.perform(post("/internal/simulator/equipments/PRESS-02/stop"))
                .andExpect(jsonPath("$.data.status").value("STOPPED"));
        assertThat(field(support.next(5000), "$.payload.status")).isEqualTo("STOPPED");
        mockMvc.perform(post("/internal/simulator/equipments/PRESS-02/run"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        assertThat(field(support.next(5000), "$.payload.status")).isEqualTo("RUNNING");

        mockMvc.perform(post("/internal/simulator/equipments/NOPE-99/fault"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        // 설비 코드 형식이 아니면 찾기 전에 400
        mockMvc.perform(post("/internal/simulator/equipments/press-1/fault"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/internal/simulator/equipments/PRESS-01x/run"))
                .andExpect(status().isBadRequest());
    }
}

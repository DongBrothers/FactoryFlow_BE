package com.factoryflow.simulator.service;

import static com.factoryflow.simulator.SimulatorTestSupport.field;
import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.simulator.SimulatorTestSupport;
import com.factoryflow.simulator.domain.EquipmentStatus;
import com.factoryflow.simulator.domain.LineProcess;
import com.factoryflow.simulator.event.publisher.EquipmentEventPublisher;
import com.factoryflow.simulator.event.publisher.payload.EquipmentStepTelemetry;
import com.factoryflow.simulator.event.publisher.payload.TelemetrySample;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

/** 로봇 공정 완료 뒤 센서값 보내기 (docs/specs/process-telemetry.md). */
@SpringBootTest(
        properties = {
            "factoryflow.simulator.start-delay=100ms",
            "factoryflow.simulator.work-time.PRESS=1s",
            "factoryflow.simulator.work-time.BODY=10s",
            "factoryflow.simulator.sample-interval=100ms",
            "factoryflow.simulator.defect-rate=0",
            "factoryflow.simulator.fault-rate=0"
        })
@Import(TestContainersConfig.class)
class TelemetryReportTest {

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired EquipmentService equipmentService;
    @Autowired EquipmentEventPublisher publisher;

    @Test
    void aRobotStepSendsItsReadingsOnceAfterTheCompletionReport() {
        SimulatorTestSupport telemetry =
                new SimulatorTestSupport(
                        rabbitTemplate,
                        objectMapper,
                        amqpAdmin,
                        List.of("equipment.step-telemetry"));

        telemetry.send(
                telemetry.dispatch(UUID.randomUUID(), "trace-tel", 31L, "PRESS", "PRESS-01", 2));

        String message = telemetry.next(5000);
        assertThat(field(message, "$.eventName")).isEqualTo("equipment.step-telemetry");
        assertThat(field(message, "$.traceId")).isEqualTo("trace-tel");
        assertThat(field(message, "$.payload.equipmentCode")).isEqualTo("PRESS-01");
        assertThat(field(message, "$.payload.workOrderId")).isEqualTo("31");
        assertThat(field(message, "$.payload.process")).isEqualTo("PRESS");
        assertThat(field(message, "$.payload.dispatchSeq")).isEqualTo("2");
        // 1초 공정을 0.1초마다 잰다
        assertThat(JsonPath.<List<Object>>read(message, "$.payload.samples")).hasSize(10);
        assertThat(JsonPath.<Map<String, Object>>read(message, "$.payload.samples[0].values"))
                .containsOnlyKeys("pressureTon", "strokeMm");
        assertThat(telemetry.next(1000)).isNull();
    }

    @Test
    void anAbandonedStepSendsNoReadings() {
        SimulatorTestSupport telemetry =
                new SimulatorTestSupport(
                        rabbitTemplate,
                        objectMapper,
                        amqpAdmin,
                        List.of("equipment.step-telemetry"));
        SimulatorTestSupport reports =
                new SimulatorTestSupport(rabbitTemplate, objectMapper, amqpAdmin);

        reports.send(reports.dispatch(UUID.randomUUID(), "t", 32L, "BODY", "BODY-02"));
        assertThat(field(reports.next(5000), "$.eventName")).isEqualTo("equipment.step-started");
        // 고장 나면 하던 작업을 버린다: 완료 보고도 센서값도 없다
        equipmentService.changeStatus("BODY-02", EquipmentStatus.FAULT);

        assertThat(telemetry.next(1500)).isNull();
        equipmentService.changeStatus("BODY-02", EquipmentStatus.RUNNING);
    }

    @Test
    void readingsAreNotKeptForResend() {
        // 센서값은 확인 대기 목록에 넣지 않는다: 브로커가 멈춰도 메모리에 쌓이지 않는다 (OOM 방지)
        int before = publisher.pendingCount();

        publisher.publishOnce(
                "t",
                new EquipmentStepTelemetry(
                        "PRESS-01",
                        33L,
                        LineProcess.PRESS,
                        1,
                        List.of(new TelemetrySample(Instant.now(), Map.of("pressureTon", 800.0)))));

        assertThat(publisher.pendingCount()).isEqualTo(before);
    }
}

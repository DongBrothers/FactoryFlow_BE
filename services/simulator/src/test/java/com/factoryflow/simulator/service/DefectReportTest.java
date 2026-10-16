package com.factoryflow.simulator.service;

import static com.factoryflow.simulator.SimulatorTestSupport.field;
import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.simulator.SimulatorTestSupport;
import com.factoryflow.test.container.TestContainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

/** 불량 확률이 1 이면 완료 보고가 DEFECT 와 공정별 사유다. */
@SpringBootTest(
        properties = {
            "factoryflow.simulator.start-delay=100ms",
            "factoryflow.simulator.work-time.PAINT=200ms",
            "factoryflow.simulator.defect-rate=1",
            "factoryflow.simulator.fault-rate=0"
        })
@Import(TestContainersConfig.class)
class DefectReportTest {

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired AmqpAdmin amqpAdmin;

    @Test
    void defectRateOneReportsDefectWithTheProcessReason() {
        SimulatorTestSupport support =
                new SimulatorTestSupport(rabbitTemplate, objectMapper, amqpAdmin);

        support.send(support.dispatch(UUID.randomUUID(), "t", 21L, "PAINT", "PAINT-02"));

        assertThat(field(support.next(5000), "$.eventName")).isEqualTo("equipment.step-started");
        String completed = support.next(5000);
        assertThat(field(completed, "$.payload.result")).isEqualTo("DEFECT");
        assertThat(field(completed, "$.payload.defectReason")).isEqualTo("도장 얼룩");
    }
}

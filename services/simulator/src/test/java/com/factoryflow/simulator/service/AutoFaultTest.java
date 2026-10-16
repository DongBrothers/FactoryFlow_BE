package com.factoryflow.simulator.service;

import static com.factoryflow.simulator.SimulatorTestSupport.field;
import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.simulator.SimulatorTestSupport;
import com.factoryflow.test.container.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

/** 자동 고장: 확률 1 이면 주기마다 가동 중인 설비가 고장 나고, 수리 시간 뒤 가동으로 돌아온다. */
@SpringBootTest(
        properties = {
            "factoryflow.simulator.fault-rate=1",
            "factoryflow.simulator.fault-check-interval=300ms",
            "factoryflow.simulator.repair-time=200ms"
        })
@Import(TestContainersConfig.class)
class AutoFaultTest {

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired AmqpAdmin amqpAdmin;

    @Test
    void equipmentFaultsAndIsRepaired() {
        SimulatorTestSupport support =
                new SimulatorTestSupport(rabbitTemplate, objectMapper, amqpAdmin);

        // 컨텍스트가 뜨는 동안 이미 고장·수리가 지나갔을 수 있어, 고장 메시지가 나올 때까지 찾는다
        String fault = null;
        for (int i = 0; i < 50 && fault == null; i++) {
            String next = support.next(5000);
            if (next == null) {
                break;
            }
            if ("FAULT".equals(field(next, "$.payload.status"))) {
                fault = next;
            }
        }
        assertThat(fault).isNotNull();
        assertThat(field(fault, "$.eventName")).isEqualTo("equipment.status-changed");
        String code = field(fault, "$.payload.equipmentCode");

        String repaired = null;
        for (int i = 0; i < 50 && repaired == null; i++) {
            String next = support.next(5000);
            if (next == null) {
                break;
            }
            if (code.equals(field(next, "$.payload.equipmentCode"))
                    && "RUNNING".equals(field(next, "$.payload.status"))) {
                repaired = next;
            }
        }
        assertThat(repaired).isNotNull();
    }
}

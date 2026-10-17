package com.factoryflow.purchase.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.factoryflow.test.container.TestContainersConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 자동 구매를 없앴다: purchase 는 inventory.shortage 를 받지 않는다 (docs/specs/purchase-manual.md "5"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class ShortageIsNotConsumedTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM purchase");
    }

    @Test
    void shortageMessageMakesNoPurchaseAndHasNoQueue() {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope =
                Map.of(
                        "eventId",
                        eventId,
                        "eventName",
                        "inventory.shortage",
                        "traceId",
                        UUID.randomUUID().toString(),
                        "version",
                        1,
                        "occurredAt",
                        Instant.now().toString(),
                        "payload",
                        Map.of(
                                "partId",
                                1012,
                                "partCode",
                                "TIRE-18",
                                "partName",
                                "타이어 18인치",
                                "shortQty",
                                3));

        rabbitTemplate.send(
                "factoryflow.events",
                "inventory.shortage",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(envelope)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setMessageId(eventId)
                        .build());

        assertThat(amqpAdmin.getQueueProperties("purchase.inventory-shortage")).isNull();
        // 받는 쪽이 있다면 처리됐을 동안 구매 건이 계속 없어야 한다
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(2))
                .until(
                        () ->
                                jdbc.queryForObject("SELECT COUNT(*) FROM purchase", Integer.class)
                                        == 0);
    }
}

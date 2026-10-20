package com.factoryflow.simulator;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

/** simulator 테스트 공통: 배정 보내기와, simulator 가 보낸 equipment.* 메시지 받기. */
public class SimulatorTestSupport {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String captureQueue;

    public SimulatorTestSupport(
            RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, AmqpAdmin amqpAdmin) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        // 연결 전용(exclusive) 임시 큐는 다른 채널에서 받을 때 사라질 수 있어 일반 큐로 만든다 (테스트 컨테이너와 함께 사라짐)
        Queue queue = new Queue("test.simulator-capture." + UUID.randomUUID(), false, false, false);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(
                BindingBuilder.bind(queue)
                        .to(new TopicExchange("factoryflow.events", true, false))
                        .with("equipment.#"));
        this.captureQueue = queue.getName();
    }

    public void purge(AmqpAdmin amqpAdmin) {
        amqpAdmin.purgeQueue(captureQueue, false);
    }

    /** mes 가 보내는 것과 같은 모양의 배정 메시지 (배정 회차 1). */
    public Map<String, Object> dispatch(
            UUID eventId, String traceId, long workOrderId, String process, String equipmentCode) {
        return dispatch(eventId, traceId, workOrderId, process, equipmentCode, 1);
    }

    /** mes 가 보내는 것과 같은 모양의 배정 메시지. */
    public Map<String, Object> dispatch(
            UUID eventId,
            String traceId,
            long workOrderId,
            String process,
            String equipmentCode,
            int dispatchSeq) {
        return Map.of(
                "eventId",
                eventId.toString(),
                "eventName",
                "mes.step-dispatched",
                "traceId",
                traceId,
                "version",
                1,
                "occurredAt",
                Instant.now().toString(),
                "payload",
                Map.of(
                        "workOrderId", workOrderId,
                        "process", process,
                        "equipmentCode", equipmentCode,
                        "dispatchSeq", dispatchSeq));
    }

    public void send(Map<String, Object> envelope) {
        rabbitTemplate.send(
                "factoryflow.events",
                "mes.step-dispatched",
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(envelope)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId((String) envelope.get("eventId"))
                        .build());
    }

    /** simulator 가 보낸 다음 equipment.* 메시지 (JSON). timeout 안에 없으면 null. */
    public String next(long timeoutMillis) {
        Message message = rabbitTemplate.receive(captureQueue, timeoutMillis);
        return message == null ? null : new String(message.getBody(), StandardCharsets.UTF_8);
    }

    public static String field(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? null : value.toString();
    }
}

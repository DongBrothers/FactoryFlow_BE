package com.factoryflow.simulator.event.publisher;

import com.factoryflow.simulator.config.EventQueueConfig;
import com.factoryflow.simulator.domain.PendingMessages;
import com.factoryflow.simulator.event.publisher.payload.EquipmentEvent;
import com.factoryflow.simulator.event.publisher.payload.OutgoingEvent;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 설비 이벤트를 RabbitMQ 로 바로 보낸다 (Outbox 없음, docs/specs/simulator.md "4. 보내기"). 메시지는 EventEnvelope 와 같은
 * JSON 이고 messageId 는 eventId 다. 브로커 확인(ack)을 받을 때까지 메모리에 두고 resendPending 으로 다시 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentEventPublisher {

    /** EventEnvelope 의 version 과 같은 값. */
    private static final int ENVELOPE_VERSION = 1;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PendingMessages<OutgoingEvent> pending = new PendingMessages<>();

    @PostConstruct
    void listenForConfirms() {
        rabbitTemplate.setConfirmCallback(
                (correlation, ack, cause) -> {
                    if (correlation == null) {
                        return;
                    }
                    if (ack) {
                        pending.acked(correlation.getId());
                    } else {
                        log.warn(
                                "브로커가 거부(nack), 다시 보냄: eventId={} reason={}",
                                correlation.getId(),
                                cause);
                    }
                });
    }

    /** 이벤트를 만들어 보낸다. 같은 흐름이면 받은 traceId 를 이어 쓴다. */
    public void publish(String traceId, EquipmentEvent payload) {
        OutgoingEvent event =
                new OutgoingEvent(
                        UUID.randomUUID(),
                        payload.eventName(),
                        traceId,
                        ENVELOPE_VERSION,
                        clock.instant(),
                        payload);
        pending.sent(event.eventId().toString(), event, clock.instant());
        send(event);
    }

    /** 확인을 못 받은 지 resendInterval 이 지난 메시지를 같은 eventId 로 다시 보낸다. */
    public void resendPending(Duration resendInterval) {
        for (OutgoingEvent event : pending.dueForResend(clock.instant(), resendInterval)) {
            log.info("확인을 못 받아 다시 보냄: eventId={} eventName={}", event.eventId(), event.eventName());
            send(event);
        }
    }

    /** 아직 확인을 못 받은 메시지 수. */
    public int pendingCount() {
        return pending.size();
    }

    private void send(OutgoingEvent event) {
        try {
            rabbitTemplate.send(
                    EventQueueConfig.EXCHANGE,
                    event.eventName(),
                    toMessage(event),
                    new CorrelationData(event.eventId().toString()));
        } catch (AmqpException e) {
            log.warn("보내기 실패, 다음 주기에 다시 보냄: eventId={}", event.eventId(), e);
        }
    }

    private Message toMessage(OutgoingEvent event) {
        return MessageBuilder.withBody(
                        objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.eventId().toString())
                .build();
    }
}

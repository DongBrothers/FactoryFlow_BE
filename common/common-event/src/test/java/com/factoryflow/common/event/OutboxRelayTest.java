package com.factoryflow.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;

class OutboxRelayTest extends EventIntegrationTest {

    private static final long RECEIVE_TIMEOUT_MS = 5_000;

    @Autowired EventPublisher eventPublisher;

    @Autowired OutboxRelay outboxRelay;

    @Autowired RabbitTemplate rabbitTemplate;

    @Autowired RabbitAdmin rabbitAdmin;

    @Autowired TopicExchange factoryFlowEventsExchange;

    @Test
    void 브로커가_ack하면_이벤트_이름을_라우팅키로_발행하고_발행완료로_기록한다() {
        Queue queue = new AnonymousQueue();
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(
                BindingBuilder.bind(queue).to(factoryFlowEventsExchange).with(TestEvent.NAME));
        EventEnvelope published = publishInTransaction(eventPublisher, new TestEvent(7L));

        outboxRelay.relay();

        EventEnvelope received =
                rabbitTemplate.receiveAndConvert(
                        queue.getName(),
                        RECEIVE_TIMEOUT_MS,
                        new ParameterizedTypeReference<EventEnvelope>() {});
        assertThat(received).isNotNull();
        assertThat(received.eventId()).isEqualTo(published.eventId());
        assertThat(received.eventName()).isEqualTo(TestEvent.NAME);
        assertThat(received.payload().get("orderId").asLong()).isEqualTo(7L);
        assertThat(unpublishedCount()).isZero();
    }

    @Test
    void 브로커가_거부하면_발행완료로_기록하지_않아_다음_주기에_다시_보낸다() {
        publishInTransaction(eventPublisher, new TestEvent(8L));
        OutboxRelay toMissingExchange =
                new OutboxRelay(
                        jdbcTemplate,
                        rabbitTemplate,
                        transactionTemplate,
                        new EventProperties("missing.exchange", 100, Duration.ofSeconds(5)),
                        Clock.systemUTC());

        toMissingExchange.relay();

        assertThat(unpublishedCount()).isEqualTo(1);
    }

    private int unpublishedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL", Integer.class);
    }
}

package com.factoryflow.order.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * order 가 받는 이벤트의 큐. 큐 이름은 {수신 서비스}.{이벤트}, 라우팅 키는 이벤트 이름 (docs/specs/events.md). 받는 이벤트마다 큐와 {큐
 * 이름}.dlq 를 한 묶음으로 만들고, 처리에 실패해 버린 메시지는 dead letter exchange 를 거쳐 그 DLQ 에 보관된다.
 */
@Slf4j
@Configuration
public class EventQueueConfig {

    public static final String STATUS_CHANGED_QUEUE = "order.production-status-changed";
    public static final String PRODUCTION_COMPLETED_QUEUE = "order.production-completed";
    public static final String PRODUCTION_CANCELLED_QUEUE = "order.production-cancelled";
    public static final String PRODUCTION_CANCEL_REJECTED_QUEUE =
            "order.production-cancel-rejected";
    static final String DEAD_LETTER_EXCHANGE = "factoryflow.events.dlx";

    // ---- 받는 이벤트 ----

    @Bean
    Declarables statusChangedQueues(TopicExchange factoryFlowEventsExchange) {
        return receive(
                factoryFlowEventsExchange, STATUS_CHANGED_QUEUE, "production.status-changed");
    }

    @Bean
    Declarables productionCancelledQueues(TopicExchange factoryFlowEventsExchange) {
        return receive(
                factoryFlowEventsExchange, PRODUCTION_CANCELLED_QUEUE, "production.cancelled");
    }

    @Bean
    Declarables productionCancelRejectedQueues(TopicExchange factoryFlowEventsExchange) {
        return receive(
                factoryFlowEventsExchange,
                PRODUCTION_CANCEL_REJECTED_QUEUE,
                "production.cancel-rejected");
    }

    @Bean
    Declarables productionCompletedQueues(TopicExchange factoryFlowEventsExchange) {
        return receive(
                factoryFlowEventsExchange, PRODUCTION_COMPLETED_QUEUE, "production.completed");
    }

    // ---- 실패 처리 ----

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    /** 재시도를 다 쓴 메시지는 큐로 돌려보내지 않고 reject 한다. 큐의 dead letter 설정에 따라 DLQ 로 옮겨진다. */
    @Bean
    MessageRecoverer messageRecoverer() {
        return (message, cause) -> {
            log.error(
                    "EVENT_DEAD_LETTERED queue={} eventId={} reason={}",
                    message.getMessageProperties().getConsumerQueue(),
                    message.getMessageProperties().getMessageId(),
                    cause.getMessage());
            throw new AmqpRejectAndDontRequeueException("재시도 횟수 초과", cause);
        };
    }

    // ---- 도우미 ----

    /**
     * 이벤트 하나를 받는 데 필요한 네 가지: 큐(실패하면 dead letter exchange 로), 이벤트 → 큐 연결, {큐}.dlq, dead letter
     * exchange → DLQ 연결. dead letter 라우팅 키를 큐 이름으로 써서 각 큐의 실패 메시지가 자기 DLQ 로 간다.
     */
    private static Declarables receive(TopicExchange events, String queue, String eventName) {
        Queue main =
                QueueBuilder.durable(queue)
                        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                        .deadLetterRoutingKey(queue)
                        .build();
        Queue deadLetter = QueueBuilder.durable(queue + ".dlq").build();
        return new Declarables(
                main,
                BindingBuilder.bind(main).to(events).with(eventName),
                deadLetter,
                BindingBuilder.bind(deadLetter)
                        .to(new DirectExchange(DEAD_LETTER_EXCHANGE))
                        .with(queue));
    }
}

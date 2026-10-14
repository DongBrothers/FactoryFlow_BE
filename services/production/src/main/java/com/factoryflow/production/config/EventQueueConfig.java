package com.factoryflow.production.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * production 이 받는 이벤트의 큐. 큐 이름은 {수신 서비스}.{이벤트}, 라우팅 키는 이벤트 이름 (docs/specs/events.md). 처리에 실패해 버린
 * 메시지는 dead letter exchange 를 거쳐 {큐 이름}.dlq 에 보관된다.
 */
@Slf4j
@Configuration
public class EventQueueConfig {

    public static final String ORDER_CREATED_QUEUE = "production.order-created";
    static final String DEAD_LETTER_EXCHANGE = "factoryflow.events.dlx";

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue orderCreatedQueue() {
        return QueueBuilder.durable(ORDER_CREATED_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ORDER_CREATED_QUEUE)
                .build();
    }

    @Bean
    Binding orderCreatedBinding(Queue orderCreatedQueue, TopicExchange factoryFlowEventsExchange) {
        return BindingBuilder.bind(orderCreatedQueue)
                .to(factoryFlowEventsExchange)
                .with("order.created");
    }

    @Bean
    Queue orderCreatedDeadLetterQueue() {
        return QueueBuilder.durable(ORDER_CREATED_QUEUE + ".dlq").build();
    }

    @Bean
    Binding orderCreatedDeadLetterBinding(
            Queue orderCreatedDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(orderCreatedDeadLetterQueue)
                .to(deadLetterExchange)
                .with(ORDER_CREATED_QUEUE);
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
}

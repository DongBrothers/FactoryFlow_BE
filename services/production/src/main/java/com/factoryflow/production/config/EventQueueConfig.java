package com.factoryflow.production.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** production 이 받는 이벤트의 큐. 큐 이름은 {수신 서비스}.{이벤트}, 라우팅 키는 이벤트 이름 (docs/specs/events.md). */
@Slf4j
@Configuration
public class EventQueueConfig {

    public static final String ORDER_CREATED_QUEUE = "production.order-created";

    @Bean
    Queue orderCreatedQueue() {
        return new Queue(ORDER_CREATED_QUEUE, true);
    }

    @Bean
    Binding orderCreatedBinding(Queue orderCreatedQueue, TopicExchange factoryFlowEventsExchange) {
        return BindingBuilder.bind(orderCreatedQueue)
                .to(factoryFlowEventsExchange)
                .with("order.created");
    }

    /** 재시도를 다 쓴 메시지는 큐로 돌려보내지 않고 버린다. 어떤 이벤트를 버렸는지 ERROR 로그로 남긴다 (DLQ 는 이후 작업). */
    @Bean
    MessageRecoverer messageRecoverer() {
        return (message, cause) -> {
            log.error(
                    "EVENT_DROPPED queue={} eventId={} reason={}",
                    message.getMessageProperties().getConsumerQueue(),
                    message.getMessageProperties().getMessageId(),
                    cause.getMessage());
            throw new AmqpRejectAndDontRequeueException("재시도 횟수 초과", cause);
        };
    }
}

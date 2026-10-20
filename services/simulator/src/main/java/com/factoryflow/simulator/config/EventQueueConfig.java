package com.factoryflow.simulator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * simulator 가 받는 이벤트의 큐와 exchange, 메시지 변환. 큐 이름은 {수신 서비스}.{이벤트}, 라우팅 키는 이벤트 이름
 * (docs/specs/events.md). 받는 이벤트마다 큐와 {큐 이름}.dlq 를 한 묶음으로 만들고, 처리에 실패해 버린 메시지는 dead letter exchange
 * 를 거쳐 그 DLQ 에 보관된다.
 */
@Slf4j
@Configuration
public class EventQueueConfig {

    public static final String STEP_DISPATCHED_QUEUE = "simulator.mes-step-dispatched";
    public static final String EXCHANGE = "factoryflow.events";
    static final String DEAD_LETTER_EXCHANGE = "factoryflow.events.dlx";

    // ---- exchange, 메시지 변환 (common-event 를 쓰지 않으므로 직접 둔다) ----

    /** 서비스들이 함께 쓰는 이벤트 exchange. common-event 의 것과 같은 이름·설정이다. */
    @Bean
    TopicExchange factoryFlowEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /**
     * @RabbitListener 가 받은 JSON 을 ReceivedEvent 로 바로 받게 한다.
     */
    @Bean
    MessageConverter eventMessageConverter(ObjectProvider<JsonMapper> jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper.getIfAvailable(JsonMapper::new));
    }

    // ---- 받는 이벤트 ----

    @Bean
    Declarables stepDispatchedQueues(TopicExchange factoryFlowEventsExchange) {
        return receive(factoryFlowEventsExchange, STEP_DISPATCHED_QUEUE, "mes.step-dispatched");
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

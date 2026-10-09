package com.factoryflow.common.event;

import java.time.Clock;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(EventProperties.class)
public class EventAutoConfiguration {

    @Bean
    TopicExchange factoryFlowEventsExchange(EventProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    /**
     * @RabbitListener 가 {@link EventEnvelope} 를 바로 받도록 JSON 변환기를 등록한다.
     */
    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    MessageConverter eventMessageConverter(ObjectProvider<JsonMapper> jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper.getIfAvailable(JsonMapper::new));
    }

    @Bean
    EventPublisher eventPublisher(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, ObjectProvider<Clock> clock) {
        return new EventPublisher(
                jdbcTemplate, objectMapper, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    OutboxRelay outboxRelay(
            JdbcTemplate jdbcTemplate,
            RabbitTemplate rabbitTemplate,
            PlatformTransactionManager transactionManager,
            EventProperties properties,
            ObjectProvider<Clock> clock) {
        return new OutboxRelay(
                jdbcTemplate,
                rabbitTemplate,
                new TransactionTemplate(transactionManager),
                properties,
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ProcessedEventGuard processedEventGuard(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectProvider<Clock> clock) {
        return new ProcessedEventGuard(
                jdbcTemplate,
                new TransactionTemplate(transactionManager),
                clock.getIfAvailable(Clock::systemUTC));
    }
}

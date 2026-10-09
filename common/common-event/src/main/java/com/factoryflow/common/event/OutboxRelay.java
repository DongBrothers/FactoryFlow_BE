package com.factoryflow.common.event;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미발행 outbox 행을 주기적으로 RabbitMQ에 발행한다. at-least-once 이므로 수신 측은 {@link ProcessedEventGuard}로 중복을 거른다.
 */
public class OutboxRelay {

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final EventProperties properties;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate,
                       TransactionTemplate transactionTemplate, EventProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${factoryflow.events.relay-interval:1s}")
    public void relay() {
        transactionTemplate.executeWithoutResult(status -> {
            List<OutboxRow> rows = jdbcTemplate.query(
                    "SELECT id, event_id, event_name, payload FROM outbox"
                            + " WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, i) -> new OutboxRow(
                            rs.getLong("id"), rs.getString("event_id"),
                            rs.getString("event_name"), rs.getString("payload")),
                    properties.relayBatchSize());

            for (OutboxRow row : rows) {
                rabbitTemplate.send(properties.exchange(), row.eventName(), toMessage(row));
                jdbcTemplate.update("UPDATE outbox SET published_at = ? WHERE id = ?",
                        Timestamp.from(clock.instant()), row.id());
            }
        });
    }

    private static Message toMessage(OutboxRow row) {
        return MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(row.eventId())
                .build();
    }

    private record OutboxRow(long id, String eventId, String eventName, String payload) {
    }
}

package com.factoryflow.common.event;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미발행 outbox 행을 주기적으로 RabbitMQ에 발행한다.
 * 브로커의 publisher confirm(ack)을 받은 행만 발행 완료로 기록하고, 나머지는 다음 주기에 다시 보낸다.
 * at-least-once 이므로 수신 측은 {@link ProcessedEventGuard}로 중복을 거른다.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final EventProperties properties;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate,
                       TransactionTemplate transactionTemplate, EventProperties properties, Clock clock) {
        if (!rabbitTemplate.getConnectionFactory().isPublisherConfirms()) {
            throw new IllegalStateException(
                    "OutboxRelay 는 publisher confirm 이 필요하다: spring.rabbitmq.publisher-confirm-type=correlated");
        }
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

            List<Sent> sent = new ArrayList<>();
            for (OutboxRow row : rows) {
                CorrelationData correlation = new CorrelationData(row.eventId());
                try {
                    rabbitTemplate.send(properties.exchange(), row.eventName(), toMessage(row), correlation);
                } catch (AmqpException e) {
                    log.warn("outbox 발행 실패, 다음 주기에 재시도: eventId={}", row.eventId(), e);
                    break;
                }
                sent.add(new Sent(row, correlation));
            }

            Timestamp now = Timestamp.from(clock.instant());
            for (Sent s : sent) {
                if (isAcked(s)) {
                    jdbcTemplate.update("UPDATE outbox SET published_at = ? WHERE id = ?", now, s.row().id());
                }
            }
        });
    }

    private boolean isAcked(Sent s) {
        try {
            CorrelationData.Confirm confirm = s.correlation().getFuture()
                    .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                log.warn("브로커가 거부(nack), 다음 주기에 재시도: eventId={}, reason={}",
                        s.row().eventId(), confirm.reason());
            }
            return confirm.ack();
        } catch (TimeoutException e) {
            log.warn("confirm 시간 초과, 다음 주기에 재시도: eventId={}", s.row().eventId());
            return false;
        } catch (ExecutionException e) {
            log.warn("confirm 실패, 다음 주기에 재시도: eventId={}", s.row().eventId(), e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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

    private record Sent(OutboxRow row, CorrelationData correlation) {
    }
}

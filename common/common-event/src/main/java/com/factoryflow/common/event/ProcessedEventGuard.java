package com.factoryflow.common.event;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 수신 멱등 처리. processed_event 에 eventId 를 기록하고, 처음 받은 이벤트일 때만 action 을 같은 트랜잭션에서 실행한다. */
public class ProcessedEventGuard {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ProcessedEventGuard(
            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * @return action 을 실행했으면 true, 이미 처리된 이벤트라 건너뛰었으면 false
     */
    public boolean runOnce(UUID eventId, Runnable action) {
        return Boolean.TRUE.equals(
                transactionTemplate.execute(
                        status -> {
                            try {
                                jdbcTemplate.update(
                                        "INSERT INTO processed_event (event_id, processed_at) VALUES (?, ?)",
                                        eventId.toString(),
                                        Timestamp.from(clock.instant()));
                            } catch (DuplicateKeyException e) {
                                return false;
                            }
                            action.run();
                            return true;
                        }));
    }
}

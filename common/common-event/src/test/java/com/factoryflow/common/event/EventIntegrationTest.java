package com.factoryflow.common.event;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = EventTestApplication.class)
abstract class EventIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM processed_event");
    }

    EventEnvelope publishInTransaction(EventPublisher publisher, DomainEvent event) {
        return transactionTemplate.execute(status -> publisher.publish(event));
    }
}

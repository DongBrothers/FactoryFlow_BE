package com.factoryflow.common.event;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = EventTestApplication.class)
abstract class EventIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;

    @Autowired InTransaction inTransaction;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM processed_event");
    }

    EventEnvelope publishInTransaction(EventPublisher publisher, DomainEvent event) {
        return inTransaction.call(() -> publisher.publish(event));
    }
}

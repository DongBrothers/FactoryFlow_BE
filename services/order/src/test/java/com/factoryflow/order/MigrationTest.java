package com.factoryflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class MigrationTest {

    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var dataSource =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var result = Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(result.targetSchemaVersion).isEqualTo("3");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsOrderTables() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        assertThat(tables).contains("customer_order", "order_item", "outbox", "processed_event");
    }

    @Test
    void rejectsNonPositiveItemQty() {
        assertThatThrownBy(() -> insertItem(1L, 1L, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_order_item_qty");
    }

    @Test
    void rejectsSameProductTwiceInOneOrder() {
        insertItem(2L, 1L, 1);

        assertThatThrownBy(() -> insertItem(2L, 1L, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_order_item_order_product");
    }

    @Test
    void processedEventKeyIsIdAndEventIdIsUnique() {
        var pk =
                jdbc.queryForList(
                        "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'processed_event'"
                                + " AND CONSTRAINT_NAME = 'PRIMARY'",
                        String.class);
        assertThat(pk).containsExactly("id");

        var eventId = "11111111-1111-1111-1111-111111111111";
        insertProcessedEvent(eventId);

        assertThatThrownBy(() -> insertProcessedEvent(eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_processed_event_event_id");
    }

    @Test
    void rejectsDuplicateOrderNo() {
        var orderNo = "550e8400-e29b-41d4-a716-446655440000";
        insertOrder(orderNo);

        assertThatThrownBy(() -> insertOrder(orderNo))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_customer_order_order_no");
    }

    private void insertOrder(String orderNo) {
        jdbc.update(
                "INSERT INTO customer_order (order_no, customer_id, status, created_at, updated_at)"
                        + " VALUES (?, 1, 'RECEIVED', NOW(6), NOW(6))",
                orderNo);
    }

    private void insertItem(long orderId, long productId, int qty) {
        jdbc.update(
                "INSERT INTO order_item (order_id, product_id, product_code, product_name, qty,"
                        + " status, created_at, updated_at)"
                        + " VALUES (?, ?, 'CAR-A', '소형차 A', ?, 'RECEIVED', NOW(6), NOW(6))",
                orderId,
                productId,
                qty);
    }

    private void insertProcessedEvent(String eventId) {
        jdbc.update(
                "INSERT INTO processed_event (event_id, processed_at) VALUES (?, NOW(6))", eventId);
    }
}

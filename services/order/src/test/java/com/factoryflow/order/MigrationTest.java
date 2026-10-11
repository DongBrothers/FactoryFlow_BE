package com.factoryflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.test.db.MigrationTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationTest extends MigrationTestSupport {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = migrateTo("3");
    }

    @Test
    void v3FillsUniqueOrderNoForExistingOrders() {
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE order_upgrade");
        var upgrade =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/order_upgrade"),
                        "root",
                        mysql.getPassword());
        Flyway.configure().dataSource(upgrade).target("2").load().migrate();
        var db = new JdbcTemplate(upgrade);
        for (int i = 0; i < 2; i++) {
            db.update(
                    "INSERT INTO customer_order (customer_id, status, created_at, updated_at)"
                            + " VALUES (7, 'RECEIVED', NOW(6), NOW(6))");
        }

        var result = Flyway.configure().dataSource(upgrade).load().migrate();

        assertThat(result.targetSchemaVersion).isEqualTo("3");
        var orderNos = db.queryForList("SELECT order_no FROM customer_order", String.class);
        assertThat(orderNos).hasSize(2).doesNotHaveDuplicates().allMatch(no -> no.length() == 36);
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

package com.factoryflow.production;

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
        assertThat(result.targetSchemaVersion).isEqualTo("2");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsProductionTables() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        assertThat(tables).contains("product", "bom", "production", "outbox", "processed_event");
    }

    @Test
    void rejectsDuplicateProductCode() {
        insertProduct("CAR-DUP");

        assertThatThrownBy(() -> insertProduct("CAR-DUP"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_product_code");
    }

    @Test
    void rejectsNonPositiveBomQty() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO bom (product_id, part_id, qty_per_unit,"
                                                + " created_at, updated_at)"
                                                + " VALUES (1, 1, 0, NOW(6), NOW(6))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_bom_qty_per_unit");
    }

    @Test
    void rejectsSecondProductionForSameOrderProduct() {
        insertProduction(10L, 1L, 1);

        assertThatThrownBy(() -> insertProduction(10L, 1L, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_production_order_product");
    }

    @Test
    void rejectsNonPositiveProductionQty() {
        assertThatThrownBy(() -> insertProduction(11L, 1L, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_production_qty");
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

        var eventId = "22222222-2222-2222-2222-222222222222";
        insertProcessedEvent(eventId);

        assertThatThrownBy(() -> insertProcessedEvent(eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_processed_event_event_id");
    }

    private void insertProduct(String code) {
        jdbc.update(
                "INSERT INTO product (code, name, created_at, updated_at)"
                        + " VALUES (?, '소형차', NOW(6), NOW(6))",
                code);
    }

    private void insertProduction(long orderId, long productId, int qty) {
        jdbc.update(
                "INSERT INTO production (order_id, product_id, qty, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'PLANNED', NOW(6), NOW(6))",
                orderId,
                productId,
                qty);
    }

    private void insertProcessedEvent(String eventId) {
        jdbc.update(
                "INSERT INTO processed_event (event_id, processed_at) VALUES (?, NOW(6))", eventId);
    }
}

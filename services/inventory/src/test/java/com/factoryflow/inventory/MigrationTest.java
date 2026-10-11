package com.factoryflow.inventory;

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
    void createsInventoryTables() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        assertThat(tables)
                .contains("part", "part_reservation", "stock_history", "outbox", "processed_event")
                .doesNotContain("stocks");
    }

    @Test
    void newPartStartsWithZeroStock() {
        jdbc.update(
                "INSERT INTO part (code, name, unit, created_at, updated_at)"
                        + " VALUES ('BRK-ZERO', '브레이크 패드', 'EA', NOW(6), NOW(6))");

        var stock =
                jdbc.queryForMap("SELECT available, reserved FROM part WHERE code = 'BRK-ZERO'");

        assertThat(stock).containsEntry("available", 0).containsEntry("reserved", 0);
    }

    @Test
    void rejectsNegativeAvailable() {
        assertThatThrownBy(() -> insertPart("BRK-NEG-A", -1, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_part_available");
    }

    @Test
    void rejectsNegativeReserved() {
        assertThatThrownBy(() -> insertPart("BRK-NEG-R", 0, -1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_part_reserved");
    }

    @Test
    void rejectsSecondReservationForSameProductionPart() {
        insertReservation(100L, 1L, 4);

        assertThatThrownBy(() -> insertReservation(100L, 1L, 4))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_part_reservation_production_part");
    }

    @Test
    void rejectsNonPositiveReservationQty() {
        assertThatThrownBy(() -> insertReservation(101L, 1L, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_part_reservation_qty");
    }

    @Test
    void rejectsNonPositiveHistoryQty() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO stock_history (part_id, qty, type, ref_type,"
                                                + " ref_id, created_at)"
                                                + " VALUES (1, -8, 'CONSUME', 'PRODUCTION', 100,"
                                                + " NOW(6))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_stock_history_qty");
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

        var eventId = "33333333-3333-3333-3333-333333333333";
        insertProcessedEvent(eventId);

        assertThatThrownBy(() -> insertProcessedEvent(eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_processed_event_event_id");
    }

    private void insertPart(String code, int available, int reserved) {
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, reserved, created_at, updated_at)"
                        + " VALUES (?, '브레이크 패드', 'EA', ?, ?, NOW(6), NOW(6))",
                code,
                available,
                reserved);
    }

    private void insertReservation(long productionId, long partId, int qty) {
        jdbc.update(
                "INSERT INTO part_reservation (production_id, part_id, qty, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'RESERVED', NOW(6), NOW(6))",
                productionId,
                partId,
                qty);
    }

    private void insertProcessedEvent(String eventId) {
        jdbc.update(
                "INSERT INTO processed_event (event_id, processed_at) VALUES (?, NOW(6))", eventId);
    }
}

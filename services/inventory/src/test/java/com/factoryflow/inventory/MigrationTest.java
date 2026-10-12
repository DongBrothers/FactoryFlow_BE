package com.factoryflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.test.db.MigrationTestSupport;
import java.sql.Connection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MigrationTest extends MigrationTestSupport {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = migrateTo("2");
    }

    @Test
    void devSeedAddsPartsWithFixedIdsAndIsRepeatable() throws Exception {
        var dataSource = migrateWithDevSeed("inventory_seed");
        var db = new JdbcTemplate(dataSource);

        assertSeed(db);
        runSeedAgain(dataSource);
        assertSeed(db);
    }

    private static void assertSeed(JdbcTemplate db) {
        assertThat(db.queryForList("SELECT id FROM part ORDER BY id", Long.class))
                .hasSize(18)
                .startsWith(1001L)
                .endsWith(1018L);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM part WHERE available <= 0", Integer.class))
                .isZero();
    }

    /** 빈 DB 를 하나 더 만들어 마이그레이션과 dev 시드(db/seed-dev)를 적용한다. */
    static DriverManagerDataSource migrateWithDevSeed(String database) {
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE " + database);
        var dataSource =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + database),
                        "root",
                        mysql.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/seed-dev")
                .load()
                .migrate();
        return dataSource;
    }

    static void runSeedAgain(DriverManagerDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/seed-dev/R__dev_seed.sql"));
        }
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

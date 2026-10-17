package com.factoryflow.purchase;

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
        // V4 가 이미 있던 자동 요청(REQUESTED)을 취소로 바꾸는지 보려고, V3 까지 올려 한 줄 넣은 뒤 끝까지 적용한다
        var v3 =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(v3).target("3").load().migrate();
        new JdbcTemplate(v3)
                .update(
                        "INSERT INTO purchase (part_id, part_code, part_name, qty, status, source,"
                                + " created_at, updated_at) VALUES (77, 'OLD-77', '옛 자동 요청', 3,"
                                + " 'REQUESTED', 'AUTO', NOW(6), NOW(6))");
        jdbc = migrateTo("4");
    }

    @Test
    void createsPurchaseTables() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        assertThat(tables).contains("supplier", "purchase", "outbox", "processed_event");
    }

    @Test
    void autoPurchaseNeedsNoSupplierOrAdmin() {
        insertAutoPurchase(4);

        var count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM purchase WHERE source = 'AUTO' AND part_id = 1"
                                + " AND supplier_id IS NULL AND requested_by IS NULL",
                        Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void v3AddsPurchaseVersionAndBaseSuppliers() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT name FROM supplier WHERE is_deleted = FALSE ORDER BY id",
                                String.class))
                .contains("현대모비스", "한국타이어", "현대트랜시스");
        insertAutoPurchase(2);
        assertThat(jdbc.queryForObject("SELECT MIN(version) FROM purchase", Long.class)).isZero();
    }

    @Test
    void v4AddsOrderedByAndCancelsPendingAutoRequests() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM purchase WHERE part_id = 77", String.class))
                .isEqualTo("CANCELLED");
        jdbc.update(
                "INSERT INTO purchase (part_id, part_code, part_name, qty, supplier_id, ordered_by,"
                        + " status, source, ordered_at, created_at, updated_at) VALUES (1012, 'TIRE-18',"
                        + " '타이어', 10, 2, '6c1e2f3a-0000-0000-0000-000000000001', 'ORDERED', 'MANUAL',"
                        + " NOW(6), NOW(6), NOW(6))");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT ordered_by FROM purchase WHERE part_id = 1012",
                                String.class))
                .isEqualTo("6c1e2f3a-0000-0000-0000-000000000001");
    }

    @Test
    void rejectsNonPositivePurchaseQty() {
        assertThatThrownBy(() -> insertAutoPurchase(0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_purchase_qty");
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

        var eventId = "44444444-4444-4444-4444-444444444444";
        insertProcessedEvent(eventId);

        assertThatThrownBy(() -> insertProcessedEvent(eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_processed_event_event_id");
    }

    private void insertAutoPurchase(int qty) {
        jdbc.update(
                "INSERT INTO purchase (part_id, part_code, part_name, qty, status, source,"
                        + " created_at, updated_at)"
                        + " VALUES (1, 'SEAT-001', '시트', ?, 'REQUESTED', 'AUTO', NOW(6), NOW(6))",
                qty);
    }

    private void insertProcessedEvent(String eventId) {
        jdbc.update(
                "INSERT INTO processed_event (event_id, processed_at) VALUES (?, NOW(6))", eventId);
    }
}

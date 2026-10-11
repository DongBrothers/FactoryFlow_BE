package com.factoryflow.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.test.db.MigrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationTest extends MigrationTestSupport {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = migrateTo("2");
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
                        "SELECT COUNT(*) FROM purchase WHERE source = 'AUTO'"
                                + " AND supplier_id IS NULL AND requested_by IS NULL",
                        Integer.class);

        assertThat(count).isEqualTo(1);
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

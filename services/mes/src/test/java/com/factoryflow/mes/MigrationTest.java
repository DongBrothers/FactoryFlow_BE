package com.factoryflow.mes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.factoryflow.test.db.MigrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** mes 마이그레이션 (docs/specs/mes-split.md): production 의 MES 테이블 최종 모양과 설비 10대. */
class MigrationTest extends MigrationTestSupport {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = migrateTo("2");
    }

    @Test
    void createsMesTablesOnly() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        assertThat(tables)
                .contains(
                        "outbox",
                        "processed_event",
                        "equipment",
                        "equipment_status_history",
                        "work_order",
                        "work_order_step",
                        "production_result")
                .doesNotContain("production", "product", "bom");
    }

    @Test
    void seedsTwoRunningEquipmentsPerProcess() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT process, COUNT(*) AS n FROM equipment"
                                        + " WHERE status = 'RUNNING' GROUP BY process"))
                .extracting(r -> r.get("process"), r -> ((Number) r.get("n")).intValue())
                .containsExactlyInAnyOrder(
                        tuple("PRESS", 2),
                        tuple("BODY", 2),
                        tuple("PAINT", 2),
                        tuple("ASSEMBLY", 2),
                        tuple("INSPECTION", 2));
    }

    @Test
    void oneWorkOrderPerProductionAndOneStepPerProcess() {
        insertWorkOrder(700, null);
        assertThatThrownBy(() -> insertWorkOrder(700, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_work_order_production");
        long workOrder =
                jdbc.queryForObject(
                        "SELECT id FROM work_order WHERE production_id = 700", Long.class);
        insertStep(workOrder, "PRESS", 1);
        assertThatThrownBy(() -> insertStep(workOrder, "PRESS", 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_work_order_step_process");
    }

    @Test
    void vinIsUniqueButManyWorkOrdersMayHaveNone() {
        insertWorkOrder(800, "KMH000017TU000001");
        assertThatThrownBy(() -> insertWorkOrder(801, "KMH000017TU000001"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_work_order_vin");
        insertWorkOrder(802, null);
        insertWorkOrder(803, null);
    }

    @Test
    void stepHasDispatchTimeAndSeqStartingAtZero() {
        assertThat(
                        jdbc.queryForMap(
                                "SELECT IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS"
                                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order_step'"
                                        + " AND COLUMN_NAME = 'dispatch_seq'"))
                .containsEntry("IS_NULLABLE", "NO")
                .containsEntry("COLUMN_DEFAULT", "0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.COLUMNS"
                                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order_step'"
                                        + " AND COLUMN_NAME = 'dispatched_at' AND IS_NULLABLE = 'YES'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void softDeleteOnlyOnEquipment() {
        // 지울 수 있는 기준 데이터(설비)만 is_deleted 를 둔다. 실적은 고치지 않아 updated_at 이 없다
        var columns =
                jdbc.queryForList(
                        "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND (COLUMN_NAME = 'is_deleted'"
                                + " OR (TABLE_NAME = 'production_result' AND COLUMN_NAME = 'updated_at'))",
                        String.class);

        assertThat(columns).containsExactly("equipment.is_deleted");
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

    private void insertWorkOrder(long productionId, String vin) {
        jdbc.update(
                "INSERT INTO work_order (production_id, order_id, product_id, status, vin,"
                        + " released_at, created_at, updated_at)"
                        + " VALUES (?, 1, 1, 'RELEASED', ?, NOW(6), NOW(6), NOW(6))",
                productionId,
                vin);
    }

    private void insertStep(long workOrderId, String process, int seq) {
        jdbc.update(
                "INSERT INTO work_order_step (work_order_id, process, seq, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'PENDING', NOW(6), NOW(6))",
                workOrderId,
                process,
                seq);
    }

    private void insertProcessedEvent(String eventId) {
        jdbc.update(
                "INSERT INTO processed_event (event_id, processed_at) VALUES (?, NOW(6))", eventId);
    }
}

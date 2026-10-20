package com.factoryflow.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.factoryflow.test.db.MigrationTestSupport;
import java.sql.Connection;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MigrationTest extends MigrationTestSupport {

    static JdbcTemplate jdbc;

    /** V10 까지만 적용한 DB. MES 테이블은 V11 에서 지우므로, V4~V10 이 만든 MES 테이블은 여기서 확인한다 (#136). */
    static JdbcTemplate atV10;

    @BeforeAll
    static void migrate() {
        jdbc = migrateTo("11");
        atV10 = new JdbcTemplate(migratedDatabase("production_at_v10", "10"));
    }

    @Test
    void devSeedAddsHyundaiProductsWithBomAndIsRepeatable() throws Exception {
        var dataSource = migrateWithDevSeed();
        var db = new JdbcTemplate(dataSource);

        assertSeed(db);
        runSeedAgain(dataSource);
        assertSeed(db);
    }

    private static void assertSeed(JdbcTemplate db) {
        assertThat(db.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(30);
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT product_id) FROM bom", Integer.class))
                .isEqualTo(28);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bom", Integer.class)).isEqualTo(176);
        assertThat(
                        db.queryForList(
                                "SELECT p.code FROM product p LEFT JOIN bom b ON b.product_id = p.id"
                                        + " WHERE b.id IS NULL ORDER BY p.code",
                                String.class))
                .containsExactly("ST1", "XCIENT-FCEV");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM bom WHERE part_id NOT BETWEEN 1001 AND 1018",
                                Integer.class))
                .isZero();
    }

    /** 빈 DB 를 하나 더 만들어 target 버전까지만 마이그레이션한다. */
    static DriverManagerDataSource migratedDatabase(String name, String target) {
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE " + name);
        var dataSource =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + name),
                        "root",
                        mysql.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
        return dataSource;
    }

    /** 빈 DB(production_seed) 를 하나 더 만들어 마이그레이션과 dev 시드(db/seed-dev)를 적용한다. */
    static DriverManagerDataSource migrateWithDevSeed() {
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE production_seed");
        var dataSource =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl()
                                .replace("/" + mysql.getDatabaseName(), "/production_seed"),
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
    void v10AddsReleasedAtAndFillsItFromExistingWorkOrders() {
        // 작업지시는 mes 로 옮긴다. production 은 발행 시각만 갖는다 (docs/specs/mes-split.md, #134)
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE production_v10");
        var dataSource =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl()
                                .replace("/" + mysql.getDatabaseName(), "/production_v10"),
                        "root",
                        mysql.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .target(MigrationVersion.fromVersion("9"))
                .load()
                .migrate();
        var db = new JdbcTemplate(dataSource);
        db.update(
                "INSERT INTO production (id, order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (1, 10, 1, 1, 'READY', NOW(6), NOW(6)), (2, 10, 1, 2, 'READY', NOW(6), NOW(6))");
        db.update(
                "INSERT INTO work_order (production_id, order_id, product_id, status, released_at,"
                        + " created_at, updated_at)"
                        + " VALUES (1, 10, 1, 'RELEASED', '2026-10-20 09:00:00', NOW(6), NOW(6))");

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(
                        db.queryForList(
                                "SELECT CAST(released_at AS CHAR) FROM production ORDER BY id",
                                String.class))
                .containsExactly("2026-10-20 09:00:00.000000", null);
    }

    @Test
    void v11DropsTheMesTablesThatMovedToMes() {
        // 작업지시·공정·실적·설비는 mes_db 로 옮겼다 (docs/specs/mes-split.md, #136)
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        assertThat(tables)
                .doesNotContain(
                        "equipment",
                        "equipment_status_history",
                        "work_order",
                        "work_order_step",
                        "production_result")
                .contains("product", "bom", "production", "outbox", "processed_event");
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
    void v4AddsTwoRunningEquipmentsPerProcess() {
        assertThat(
                        atV10.queryForList(
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
        atV10.update(
                "INSERT INTO work_order (production_id, order_id, product_id, status, released_at,"
                        + " created_at, updated_at) VALUES (700, 1, 1, 'RELEASED', NOW(6), NOW(6), NOW(6))");
        assertThatThrownBy(
                        () ->
                                atV10.update(
                                        "INSERT INTO work_order (production_id, order_id, product_id, status,"
                                                + " released_at, created_at, updated_at)"
                                                + " VALUES (700, 1, 1, 'RELEASED', NOW(6), NOW(6), NOW(6))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_work_order_production");
        long workOrder =
                atV10.queryForObject(
                        "SELECT id FROM work_order WHERE production_id = 700", Long.class);
        insertStepAtV10(workOrder, "PRESS", 1);
        assertThatThrownBy(() -> insertStepAtV10(workOrder, "PRESS", 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_work_order_step_process");
    }

    @Test
    void v7AddsStepDispatchedAt() {
        assertThat(
                        atV10.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.COLUMNS"
                                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order_step'"
                                        + " AND COLUMN_NAME = 'dispatched_at' AND IS_NULLABLE = 'YES'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v8AddsStepDispatchSeqStartingAtZero() {
        assertThat(
                        atV10.queryForMap(
                                "SELECT IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS"
                                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order_step'"
                                        + " AND COLUMN_NAME = 'dispatch_seq'"))
                .containsEntry("IS_NULLABLE", "NO")
                .containsEntry("COLUMN_DEFAULT", "0");
    }

    @Test
    void v9KeepsSoftDeleteOnlyOnMasterDataAndDropsUnreadColumns() {
        // 지울 수 있는 기준 데이터(제품, BOM, 설비)만 is_deleted 를 둔다. 생산 건의 시작·완료 시각은 작업지시에만,
        // 고치지 않는 실적은 updated_at 없이 둔다 (#123)
        var columns =
                atV10.queryForList(
                        "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND (COLUMN_NAME = 'is_deleted'"
                                + " OR (TABLE_NAME IN ('production', 'work_order')"
                                + " AND COLUMN_NAME IN ('started_at', 'completed_at'))"
                                + " OR (TABLE_NAME = 'production_result' AND COLUMN_NAME = 'updated_at'))",
                        String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "product.is_deleted",
                        "bom.is_deleted",
                        "equipment.is_deleted",
                        "work_order.started_at",
                        "work_order.completed_at");
    }

    @Test
    void v6CreatesEquipmentStatusHistory() {
        atV10.update(
                "INSERT INTO equipment_status_history (equipment_code, from_status, to_status,"
                        + " changed_at, created_at) VALUES ('PRESS-01', 'RUNNING', 'FAULT', NOW(6), NOW(6))");

        assertThat(
                        atV10.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.STATISTICS"
                                        + " WHERE TABLE_SCHEMA = DATABASE()"
                                        + " AND TABLE_NAME = 'equipment_status_history'"
                                        + " AND INDEX_NAME = 'idx_equipment_status_history_equipment'",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void v5MakesWorkOrderVinUnique() {
        atV10.update(
                "INSERT INTO work_order (production_id, order_id, product_id, status, vin, released_at,"
                        + " created_at, updated_at)"
                        + " VALUES (800, 1, 1, 'COMPLETED', 'KMH000017TU000001', NOW(6), NOW(6), NOW(6))");
        assertThatThrownBy(
                        () ->
                                atV10.update(
                                        "INSERT INTO work_order (production_id, order_id, product_id, status,"
                                                + " vin, released_at, created_at, updated_at)"
                                                + " VALUES (801, 1, 1, 'COMPLETED', 'KMH000017TU000001',"
                                                + " NOW(6), NOW(6), NOW(6))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_work_order_vin");
        // 완료 전 작업지시(vin NULL)는 여러 건이어도 된다
        atV10.update(
                "INSERT INTO work_order (production_id, order_id, product_id, status, released_at,"
                        + " created_at, updated_at) VALUES (802, 1, 1, 'RELEASED', NOW(6), NOW(6), NOW(6))");
        atV10.update(
                "INSERT INTO work_order (production_id, order_id, product_id, status, released_at,"
                        + " created_at, updated_at) VALUES (803, 1, 1, 'RELEASED', NOW(6), NOW(6), NOW(6))");
    }

    @Test
    void oneProductionPerVehicleOfOrderProduct() {
        // 생산 건은 차량 1대 (V3): 같은 주문·제품의 차량 번호(seq)는 하나씩만
        insertProduction(10L, 1L, 1);
        insertProduction(10L, 1L, 2);

        assertThatThrownBy(() -> insertProduction(10L, 1L, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_production_order_product_seq");
    }

    @Test
    void rejectsNonPositiveVehicleSeq() {
        assertThatThrownBy(() -> insertProduction(11L, 1L, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_production_seq");
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

    private void insertProduction(long orderId, long productId, int seq) {
        jdbc.update(
                "INSERT INTO production (order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'PLANNED', NOW(6), NOW(6))",
                orderId,
                productId,
                seq);
    }

    private void insertStepAtV10(long workOrderId, String process, int seq) {
        atV10.update(
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

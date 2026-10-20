package com.factoryflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.test.db.MigrationTestSupport;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.Map;
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
        jdbc = migrateTo("9");
    }

    @Test
    void devSeedAddsPartsWithFixedIdsAndIsRepeatable() throws Exception {
        var dataSource = migrateWithDevSeed();
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
        // 모든 재고에 LOT 이 있다: 시드 부품마다 초기 재고 LOT 하나, 수량은 실물 그대로 (다시 돌려도 두 번 잡히지 않음, #116)
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM part p JOIN part_lot l ON l.part_id = p.id"
                                        + " AND l.lot_no = CONCAT('LOT-INIT-', p.id)"
                                        + " WHERE l.purchase_id IS NULL"
                                        + " AND l.remaining_qty = p.available + p.reserved",
                                Integer.class))
                .isEqualTo(18);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM part_lot", Integer.class)).isEqualTo(18);
    }

    /** 빈 DB(inventory_seed) 를 하나 더 만들어 마이그레이션과 dev 시드(db/seed-dev)를 적용한다. */
    static DriverManagerDataSource migrateWithDevSeed() {
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE inventory_seed");
        var dataSource =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl()
                                .replace("/" + mysql.getDatabaseName(), "/inventory_seed"),
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
    void v3FinishedVehicleIsOnePerVinAndPerProduction() {
        insertFinishedVehicle("KMH000017TU000001", 500L);

        assertThatThrownBy(() -> insertFinishedVehicle("KMH000017TU000001", 501L))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_finished_vehicle_vin");
        assertThatThrownBy(() -> insertFinishedVehicle("KMH000017TU000002", 500L))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_finished_vehicle_production");
    }

    @Test
    void v4FinishedVehicleStartsNotShippedWithVersionZero() {
        insertFinishedVehicle("KMH000017TU000003", 502L);

        assertThat(
                        jdbc.queryForMap(
                                "SELECT shipped_at, version FROM finished_vehicle WHERE vin = ?",
                                "KMH000017TU000003"))
                .containsEntry("shipped_at", null)
                .containsEntry("version", 0L);
    }

    @Test
    void v5PartLotIsOnePerLotNoAndPerPurchase() {
        insertLot("LOT-261017-000501", 501L, 5, 5);

        assertThatThrownBy(() -> insertLot("LOT-261017-000501", 502L, 5, 5))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_part_lot_lot_no");
        assertThatThrownBy(() -> insertLot("LOT-261017-000502", 501L, 5, 5))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_part_lot_purchase");
    }

    @Test
    void v5PartLotNoFitsTheLongestPurchaseId() {
        String lotNo = "LOT-261017-" + Long.MAX_VALUE;

        insertLot(lotNo, Long.MAX_VALUE, 5, 5);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT lot_no FROM part_lot WHERE purchase_id = ?",
                                String.class,
                                Long.MAX_VALUE))
                .isEqualTo(lotNo);
    }

    @Test
    void v5PartLotQtyStaysInRange() {
        assertThatThrownBy(() -> insertLot("LOT-261017-000511", 511L, 0, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_part_lot_received_qty");
        assertThatThrownBy(() -> insertLot("LOT-261017-000512", 512L, 5, -1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_part_lot_remaining_qty");
        assertThatThrownBy(() -> insertLot("LOT-261017-000513", 513L, 5, 6))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_part_lot_remaining_qty");
    }

    @Test
    void v5PartLotUsageQtyIsPositive() {
        jdbc.update(
                "INSERT INTO part_lot_usage (vin, lot_id, qty, created_at)"
                        + " VALUES ('KMH000017TU000521', 1, 1, NOW(6))");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO part_lot_usage (vin, lot_id, qty,"
                                                + " created_at) VALUES"
                                                + " ('KMH000017TU000522', 1, 0, NOW(6))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_part_lot_usage_qty");
    }

    @Test
    void v6UsageNeedsALotAndInitialLotHasNoPurchase() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO part_lot_usage (vin, lot_id, qty,"
                                                + " created_at) VALUES"
                                                + " ('KMH000017TU000531', NULL, 1, NOW(6))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("lot_id");
        jdbc.update(
                "INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty,"
                        + " received_at, created_at, updated_at)"
                        + " VALUES (1, 'LOT-INIT-1', NULL, 5, 5, NOW(6), NOW(6), NOW(6))");
    }

    @Test
    void v6GivesStockWithoutLotAnInitialLot() {
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE inventory_v6");
        var upgrade =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/inventory_v6"),
                        "root",
                        mysql.getPassword());
        Flyway.configure().dataSource(upgrade).target("5").load().migrate();
        var db = new JdbcTemplate(upgrade);
        // 타이어: 실물 10 = LOT 3 + LOT 없음 7, LOT 없이 이미 4 를 썼다. 엔진: 실물 0, 쓴 것 없음. 시트: 실물 0, LOT 없이 2 를
        // 썼다
        db.update(
                "INSERT INTO part (id, code, name, unit, available, reserved, created_at,"
                        + " updated_at) VALUES (1, 'TIRE', '타이어', 'EA', 8, 2, NOW(6), NOW(6)),"
                        + " (2, 'ENG', '엔진', 'EA', 0, 0, NOW(6), NOW(6)),"
                        + " (3, 'SEAT', '시트', 'EA', 0, 0, NOW(6), NOW(6))");
        db.update(
                "INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty,"
                        + " received_at, created_at, updated_at)"
                        + " VALUES (1, 'LOT-261001-000001', 1, 5, 3, NOW(6), NOW(6), NOW(6))");
        db.update(
                "INSERT INTO part_lot_usage (vin, production_id, part_id, lot_id, qty, created_at)"
                        + " VALUES ('KMH000017TU000541', 541, 1, NULL, 4, NOW(6)),"
                        + " ('KMH000017TU000541', 541, 1, 1, 2, NOW(6)),"
                        + " ('KMH000017TU000542', 542, 3, NULL, 2, NOW(6))");

        Timestamp before = db.queryForObject("SELECT NOW(6)", Timestamp.class);
        Flyway.configure().dataSource(upgrade).target("6").load().migrate();
        Timestamp after = db.queryForObject("SELECT NOW(6)", Timestamp.class);

        assertThat(
                        db.queryForList(
                                "SELECT part_id, lot_no, received_qty, remaining_qty"
                                        + " FROM part_lot WHERE lot_no LIKE 'LOT-INIT-%'"
                                        + " ORDER BY part_id"))
                .containsExactly(
                        Map.of(
                                "part_id",
                                1L,
                                "lot_no",
                                "LOT-INIT-1",
                                "received_qty",
                                11,
                                "remaining_qty",
                                7),
                        Map.of(
                                "part_id",
                                3L,
                                "lot_no",
                                "LOT-INIT-3",
                                "received_qty",
                                2,
                                "remaining_qty",
                                0));
        assertThat(
                        db.queryForList(
                                "SELECT l.lot_no FROM part_lot_usage u JOIN part_lot l"
                                        + " ON l.id = u.lot_id ORDER BY u.id",
                                String.class))
                .containsExactly("LOT-INIT-1", "LOT-261001-000001", "LOT-INIT-3");
        // 초기 재고 LOT 은 id 가 커도 입고 시각이 그 부품의 기존 LOT 보다 앞이라 가장 먼저 쓰인다
        assertThat(
                        db.queryForList(
                                "SELECT lot_no FROM part_lot WHERE part_id = 1"
                                        + " ORDER BY received_at, id",
                                String.class))
                .containsExactly("LOT-INIT-1", "LOT-261001-000001");
        // LOT 이 없던 부품의 초기 재고 LOT 은 마이그레이션 시각에 들어온 것으로 본다
        assertThat(
                        db.queryForObject(
                                "SELECT received_at FROM part_lot WHERE lot_no = 'LOT-INIT-3'",
                                Timestamp.class))
                .isBetween(before, after);
        // 기존 LOT 이 있던 부품의 초기 재고 LOT 은 가장 이른 LOT 보다 정확히 1초 앞이다
        assertThat(
                        db.queryForObject(
                                "SELECT TIMESTAMPDIFF(MICROSECOND, i.received_at, l.received_at)"
                                        + " FROM part_lot i JOIN part_lot l"
                                        + " ON l.lot_no = 'LOT-261001-000001'"
                                        + " WHERE i.lot_no = 'LOT-INIT-1'",
                                Long.class))
                .isEqualTo(1_000_000L);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM part_lot WHERE lot_no LIKE 'LOT-INIT-%'"
                                        + " AND purchase_id IS NULL",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void v7KeepsLotUsageByVinAndLotAndSoftDeleteOnlyOnParts() {
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE inventory_v7");
        var upgrade =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/inventory_v7"),
                        "root",
                        mysql.getPassword());
        Flyway.configure().dataSource(upgrade).target("6").load().migrate();
        var db = new JdbcTemplate(upgrade);
        db.update(
                "INSERT INTO part_lot_usage (vin, production_id, part_id, lot_id, qty, created_at)"
                        + " VALUES ('KMH000017TU000551', 551, 1, 7, 4, NOW(6))");

        Flyway.configure().dataSource(upgrade).target("7").load().migrate();

        // 부품은 LOT 이, 생산 건은 VIN 의 완성차가 갖고 있어 사용 기록에는 vin, lot_id, qty 만 남긴다 (#123)
        assertThat(db.queryForMap("SELECT vin, lot_id, qty FROM part_lot_usage"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("vin", "KMH000017TU000551", "lot_id", 7L, "qty", 4));
        assertThat(
                        db.queryForList(
                                "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME)"
                                        + " FROM information_schema.COLUMNS"
                                        + " WHERE TABLE_SCHEMA = DATABASE() AND (COLUMN_NAME = 'is_deleted'"
                                        + " OR TABLE_NAME = 'part_lot_usage')",
                                String.class))
                .containsExactlyInAnyOrder(
                        "part.is_deleted",
                        "part_lot_usage.id",
                        "part_lot_usage.vin",
                        "part_lot_usage.lot_id",
                        "part_lot_usage.qty",
                        "part_lot_usage.created_at");
    }

    @Test
    void v8StockAdjustmentKeepsSignedQtyButNotZero() {
        // 재고 조정은 늘리면 양수, 줄이면 음수 (docs/specs/stock-adjustment.md)
        jdbc.update(
                "INSERT INTO stock_adjustment (part_id, qty, reason, adjusted_by, created_at)"
                        + " VALUES (1, -2, '운반 중 파손', '6c1e2f3a-0000-0000-0000-000000000001', NOW(6))");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT qty FROM stock_adjustment WHERE reason = '운반 중 파손'",
                                Integer.class))
                .isEqualTo(-2);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO stock_adjustment (part_id, qty, reason, adjusted_by,"
                                                + " created_at) VALUES (1, 0, '실사', 'x', NOW(6))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_stock_adjustment_qty");
    }

    private void insertLot(String lotNo, long purchaseId, int receivedQty, int remainingQty) {
        jdbc.update(
                "INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty,"
                        + " received_at, created_at, updated_at)"
                        + " VALUES (1, ?, ?, ?, ?, NOW(6), NOW(6), NOW(6))",
                lotNo,
                purchaseId,
                receivedQty,
                remainingQty);
    }

    @Test
    void v9CreatesFortySlotsInTwoZonesAndOneVehiclePerSlot() {
        // 완성차 보관 위치 (docs/specs/finished-vehicle-location.md, #138)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_slot", Integer.class))
                .isEqualTo(40);
        assertThat(
                        jdbc.queryForList(
                                "SELECT code FROM storage_slot WHERE code IN ('A-01', 'A-20', 'B-01', 'B-20')"
                                        + " ORDER BY code",
                                String.class))
                .containsExactly("A-01", "A-20", "B-01", "B-20");
        assertThat(
                        jdbc.queryForList(
                                "SELECT zone, COUNT(*) AS n FROM storage_slot GROUP BY zone ORDER BY zone"))
                .extracting(r -> r.get("zone"), r -> ((Number) r.get("n")).intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A", 20),
                        org.assertj.core.groups.Tuple.tuple("B", 20));
        jdbc.update("UPDATE storage_slot SET finished_vehicle_id = 900 WHERE code = 'A-01'");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE storage_slot SET finished_vehicle_id = 900 WHERE code = 'A-02'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_storage_slot_vehicle");
        jdbc.update("UPDATE storage_slot SET finished_vehicle_id = NULL");
    }

    @Test
    void v9GivesVehiclesInStockASlotInIdOrder() {
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE inventory_v9");
        var upgrade =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/inventory_v9"),
                        "root",
                        mysql.getPassword());
        Flyway.configure().dataSource(upgrade).target("8").load().migrate();
        var db = new JdbcTemplate(upgrade);
        // 창고에 있는 차 두 대(1, 3)와 출고된 차 한 대(2)
        db.update(
                "INSERT INTO finished_vehicle (id, vin, production_id, order_id, product_id, status,"
                        + " created_at, updated_at) VALUES"
                        + " (1, 'KMH000017TU000001', 1, 1, 1, 'IN_STOCK', NOW(6), NOW(6)),"
                        + " (2, 'KMH000017TU000002', 2, 1, 1, 'SHIPPED', NOW(6), NOW(6)),"
                        + " (3, 'KMH000017TU000003', 3, 1, 1, 'IN_STOCK', NOW(6), NOW(6))");

        Flyway.configure().dataSource(upgrade).target("9").load().migrate();

        assertThat(db.queryForList("SELECT id, slot_code FROM finished_vehicle ORDER BY id"))
                .extracting(r -> ((Number) r.get("id")).longValue(), r -> r.get("slot_code"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, "A-01"),
                        org.assertj.core.groups.Tuple.tuple(2L, null),
                        org.assertj.core.groups.Tuple.tuple(3L, "A-02"));
        assertThat(
                        db.queryForList(
                                "SELECT code, finished_vehicle_id FROM storage_slot"
                                        + " WHERE finished_vehicle_id IS NOT NULL ORDER BY code"))
                .extracting(
                        r -> r.get("code"),
                        r -> ((Number) r.get("finished_vehicle_id")).longValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A-01", 1L),
                        org.assertj.core.groups.Tuple.tuple("A-02", 3L));
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

    private void insertFinishedVehicle(String vin, long productionId) {
        jdbc.update(
                "INSERT INTO finished_vehicle (vin, production_id, order_id, product_id, status,"
                        + " created_at, updated_at) VALUES (?, ?, 1, 1, 'IN_STOCK', NOW(6), NOW(6))",
                vin,
                productionId);
    }

    private void insertProcessedEvent(String eventId) {
        jdbc.update(
                "INSERT INTO processed_event (event_id, processed_at) VALUES (?, NOW(6))", eventId);
    }
}

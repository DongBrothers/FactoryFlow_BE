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
        jdbc = migrateTo("7");
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

        var result = Flyway.configure().dataSource(upgrade).target("3").load().migrate();

        assertThat(result.targetSchemaVersion).isEqualTo("3");
        var orderNos = db.queryForList("SELECT order_no FROM customer_order", String.class);
        assertThat(orderNos).hasSize(2).doesNotHaveDuplicates().allMatch(no -> no.length() == 36);
    }

    @Test
    void v7TurnsWaitingMaterialIntoReceived() {
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE order_v7");
        var upgrade =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/order_v7"),
                        "root",
                        mysql.getPassword());
        Flyway.configure().dataSource(upgrade).target("6").load().migrate();
        var db = new JdbcTemplate(upgrade);
        db.update(
                "INSERT INTO customer_order (order_no, customer_id, status, created_at, updated_at)"
                        + " VALUES (UUID(), 7, 'WAITING_MATERIAL', NOW(6), NOW(6))");
        db.update(
                "INSERT INTO order_item (order_id, product_id, product_code, product_name, qty,"
                        + " status, created_at, updated_at)"
                        + " SELECT id, 1, 'CAR-1', '차 1', 2, 'WAITING_MATERIAL', NOW(6), NOW(6)"
                        + " FROM customer_order");
        db.update(
                "INSERT INTO order_vehicle (order_id, order_item_id, production_id, status,"
                        + " created_at, updated_at) SELECT order_id, id, 101, 'WAITING_MATERIAL',"
                        + " NOW(6), NOW(6) FROM order_item");
        db.update(
                "INSERT INTO order_vehicle (order_id, order_item_id, production_id, status,"
                        + " created_at, updated_at) SELECT order_id, id, 102, 'IN_PRODUCTION',"
                        + " NOW(6), NOW(6) FROM order_item");

        Flyway.configure().dataSource(upgrade).target("7").load().migrate();

        assertThat(db.queryForObject("SELECT status FROM customer_order", String.class))
                .isEqualTo("RECEIVED");
        assertThat(db.queryForObject("SELECT status FROM order_item", String.class))
                .isEqualTo("RECEIVED");
        assertThat(
                        db.queryForList(
                                "SELECT status FROM order_vehicle ORDER BY production_id",
                                String.class))
                .containsExactly("RECEIVED", "IN_PRODUCTION");
    }

    @Test
    void createsOrderTables() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        assertThat(tables)
                .contains(
                        "customer_order",
                        "order_item",
                        "order_vehicle",
                        "outbox",
                        "processed_event");
    }

    @Test
    void rejectsNonPositiveItemQty() {
        assertThatThrownBy(() -> insertItem(1L, 1L, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_order_item_qty");
    }

    @Test
    void v6AddsCancelTimes() {
        var columns =
                jdbc.queryForList(
                        "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME IN"
                                + " ('cancel_requested_at', 'cancelled_at') AND IS_NULLABLE = 'YES'",
                        String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "customer_order.cancel_requested_at", "order_vehicle.cancelled_at");
    }

    @Test
    void v5AddsVehicleVinAndOrderVersions() {
        var columns =
                jdbc.queryForList(
                        "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND ((TABLE_NAME = 'order_vehicle'"
                                + " AND COLUMN_NAME = 'vin') OR (TABLE_NAME IN ('customer_order',"
                                + " 'order_item') AND COLUMN_NAME = 'version'))",
                        String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "order_vehicle.vin", "customer_order.version", "order_item.version");
    }

    @Test
    void oneVehiclePerProduction() {
        // 차량 = 생산 건 1건 (V4). 같은 생산 건을 두 번 저장하지 않는다
        insertVehicle(3L, 1L, 501L);

        assertThatThrownBy(() -> insertVehicle(3L, 1L, 501L))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_order_vehicle_production");
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

    private void insertVehicle(long orderId, long orderItemId, long productionId) {
        jdbc.update(
                "INSERT INTO order_vehicle (order_id, order_item_id, production_id, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, 'RECEIVED', NOW(6), NOW(6))",
                orderId,
                orderItemId,
                productionId);
    }

    private void insertProcessedEvent(String eventId) {
        jdbc.update(
                "INSERT INTO processed_event (event_id, processed_at) VALUES (?, NOW(6))", eventId);
    }
}

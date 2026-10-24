package com.factoryflow.inventory.event.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.test.container.TestContainersConfig;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 폐기된 차가 잡아 둔 부품을 폐기 소모한다 (docs/specs/defect-scrap.md "3. inventory", #182). */
@SpringBootTest
@Import(TestContainersConfig.class)
class ProductionScrappedTest {

    @Autowired ProductionEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    long engine;
    long tire;
    long engineInit;
    long tireInit;
    long tireLot;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM part_lot");
        jdbc.update("DELETE FROM finished_vehicle");
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM part_reservation");
        jdbc.update("DELETE FROM part");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        // 생산 건 100 이 엔진 1, 타이어 4 를 잡아 두었다. 타이어 LOT: 초기 재고 2 + 입고 LOT 5
        engine = insertPart("ENG-T", 9, 1);
        tire = insertPart("TIRE-T", 3, 4);
        engineInit = insertLot(engine, "LOT-INIT-" + engine, null, 10);
        tireInit = insertLot(tire, "LOT-INIT-" + tire, null, 2);
        tireLot = insertLot(tire, "LOT-261001-000001", 1L, 5);
        insertReservation(100L, engine, 1);
        insertReservation(100L, tire, 4);
    }

    @Test
    void scrappedCarUsesUpItsReservedPartsWithoutAFinishedVehicle() {
        listener.onProductionScrapped(scrapped(100L));

        assertThat(jdbc.queryForList("SELECT status FROM part_reservation", String.class))
                .containsOnly("CONSUMED");
        assertThat(stock(engine)).containsEntry("available", 9).containsEntry("reserved", 0);
        assertThat(stock(tire)).containsEntry("available", 3).containsEntry("reserved", 0);
        // 폐기도 LOT 을 입고 순으로 뺀다: 타이어 4 = 초기 재고 2 + 입고 LOT 2
        assertThat(remaining(engineInit)).isEqualTo(9);
        assertThat(remaining(tireInit)).isZero();
        assertThat(remaining(tireLot)).isEqualTo(3);
        assertThat(
                        jdbc.queryForList(
                                "SELECT CONCAT(type, ':', ref_type, ':', ref_id, ':', qty)"
                                        + " FROM stock_history ORDER BY part_id",
                                String.class))
                .containsExactly("SCRAP:PRODUCTION:100:1", "SCRAP:PRODUCTION:100:4");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finished_vehicle", Integer.class))
                .isZero();
    }

    @Test
    void sameScrapTwiceIsAppliedOnce() {
        EventEnvelope event = scrapped(100L);

        listener.onProductionScrapped(event);
        listener.onProductionScrapped(event);
        listener.onProductionScrapped(scrapped(100L));

        assertThat(remaining(tireLot)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history", Integer.class))
                .isEqualTo(2);
    }

    private EventEnvelope scrapped(long productionId) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "production.scrapped",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(Map.of("orderId", 42L, "productionId", productionId)));
    }

    private Map<String, Object> stock(long partId) {
        return jdbc.queryForMap("SELECT available, reserved FROM part WHERE id = ?", partId);
    }

    private int remaining(long lotId) {
        return jdbc.queryForObject(
                "SELECT remaining_qty FROM part_lot WHERE id = ?", Integer.class, lotId);
    }

    private long insertPart(String code, int available, int reserved) {
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, reserved, created_at, updated_at)"
                        + " VALUES (?, ?, 'EA', ?, ?, NOW(6), NOW(6))",
                code,
                code,
                available,
                reserved);
        return jdbc.queryForObject("SELECT id FROM part WHERE code = ?", Long.class, code);
    }

    private long insertLot(long partId, String lotNo, Long purchaseId, int qty) {
        jdbc.update(
                "INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty,"
                        + " received_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6), NOW(6))",
                partId,
                lotNo,
                purchaseId,
                qty,
                qty);
        return jdbc.queryForObject("SELECT id FROM part_lot WHERE lot_no = ?", Long.class, lotNo);
    }

    private void insertReservation(long productionId, long partId, int qty) {
        jdbc.update(
                "INSERT INTO part_reservation (production_id, part_id, qty, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'RESERVED', NOW(6), NOW(6))",
                productionId,
                partId,
                qty);
    }
}

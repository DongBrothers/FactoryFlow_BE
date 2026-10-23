package com.factoryflow.inventory.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.web.exception.CustomException;
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

/** 입고 때 LOT 부여, 생산 완료 때 소모 LOT 기록 (docs/specs/part-lot.md "1. 입고", "2. 생산 완료"). */
@SpringBootTest
@Import(TestContainersConfig.class)
class PartLotAllocationTest {

    static final String VIN_1 = "KMH000017TU000100";
    static final String VIN_2 = "KMH000017TU000101";

    @Autowired PurchaseEventListener purchaseListener;
    @Autowired ProductionEventListener productionListener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    long engine;
    long tire;
    long engineInit;
    long tireInit;
    long lot1;
    long lot2;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM part_lot");
        jdbc.update("DELETE FROM finished_vehicle");
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM part_reservation");
        jdbc.update("DELETE FROM part");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        // 모든 재고에 LOT 이 있다 (#116). 엔진: 실물 10 = 초기 재고 LOT 10. 타이어: 실물 10 = 초기 재고 LOT 2 + LOT1 3 +
        // LOT2 5
        // 생산 건 100 은 엔진 1, 타이어 4 를, 101 은 타이어 4 를 잡아 두었다
        engine = insertPart("ENG-T", 9, 1);
        tire = insertPart("TIRE-T", 2, 8);
        engineInit = insertLot(engine, "LOT-INIT-" + engine, null, 10);
        tireInit = insertLot(tire, "LOT-INIT-" + tire, null, 2);
        lot1 = insertLot(tire, "LOT-261001-000001", 1L, 3);
        lot2 = insertLot(tire, "LOT-261002-000002", 2L, 5);
        insertReservation(100L, engine, 1);
        insertReservation(100L, tire, 4);
        insertReservation(101L, tire, 4);
    }

    @Test
    void receiptGetsOneLot() {
        purchaseListener.onReceived(received(31L, tire, 5));

        assertThat(
                        jdbc.queryForMap(
                                "SELECT part_id, lot_no, received_qty, remaining_qty, received_at"
                                        + " FROM part_lot WHERE purchase_id = 31"))
                .containsEntry("part_id", tire)
                .containsEntry("received_qty", 5)
                .containsEntry("remaining_qty", 5)
                .hasEntrySatisfying(
                        "lot_no", n -> assertThat((String) n).matches("LOT-\\d{6}-000031"))
                .hasEntrySatisfying("received_at", t -> assertThat(t).isNotNull());
    }

    @Test
    void sameReceiptTwiceGetsOneLot() {
        purchaseListener.onReceived(received(31L, tire, 5));
        purchaseListener.onReceived(received(31L, tire, 5));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM part_lot WHERE purchase_id = 31",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void completedTakesLotsInReceiptOrderStartingWithInitialStock() {
        productionListener.onProductionCompleted(completed(100L, VIN_1));

        // 타이어 4 = 초기 재고 LOT 2 + LOT1 2, 엔진 1 = 초기 재고 LOT 1 (어느 차에 들어갔는지는 mes 가 갖는다, #176)
        assertThat(remaining(engineInit)).isEqualTo(9);
        assertThat(remaining(tireInit)).isZero();
        assertThat(remaining(lot1)).isEqualTo(1);
        assertThat(remaining(lot2)).isEqualTo(5);

        productionListener.onProductionCompleted(completed(101L, VIN_2));

        // 초기 재고 LOT 을 다 써서 LOT1 의 남은 1, 그다음 LOT2 에서 3 (한 부품이 LOT 두 개에 걸친다)
        assertThat(remaining(lot1)).isZero();
        assertThat(remaining(lot2)).isEqualTo(2);
        assertLotsWithinStock();
    }

    @Test
    void sameCompletedTwiceTakesLotsOnce() {
        productionListener.onProductionCompleted(completed(100L, VIN_1));
        productionListener.onProductionCompleted(completed(100L, VIN_1));

        assertThat(consumeHistories()).isEqualTo(2);
        assertThat(remaining(engineInit)).isEqualTo(9);
        assertThat(remaining(tireInit)).isZero();
        assertThat(remaining(lot1)).isEqualTo(1);
    }

    @Test
    void initialLotMadeAfterAPurchaseLotIsStillTakenFirst() {
        // V6 처럼 기존 LOT(id 작음) 뒤에 초기 재고 LOT(id 큼)이 생겨도, 입고 시각이 앞이라 먼저 쓴다
        jdbc.update("DELETE FROM part_lot WHERE id = ?", engineInit);
        long purchased = insertLot(engine, "LOT-261003-000003", 3L, 5);
        long initial = insertLot(engine, "LOT-INIT-" + engine, null, 5);
        jdbc.update(
                "UPDATE part_lot SET received_at = NOW(6) - INTERVAL 1 DAY WHERE id = ?", initial);

        productionListener.onProductionCompleted(completed(100L, VIN_1));

        assertThat(remaining(initial)).isEqualTo(4);
        assertThat(remaining(purchased)).isEqualTo(5);
    }

    @Test
    void lotsShortOfReservedStockFailWithoutTakingAnything() {
        // 실물과 LOT 이 어긋난 데이터: 타이어 LOT 남은 수량 합이 잡아 둔 4 보다 적다
        jdbc.update("UPDATE part_lot SET remaining_qty = 0 WHERE part_id = ?", tire);
        jdbc.update("UPDATE part_lot SET remaining_qty = 1 WHERE id = ?", lot2);

        assertThatThrownBy(() -> productionListener.onProductionCompleted(completed(100L, VIN_1)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("LOT 남은 수량");
        assertThat(consumeHistories()).isZero();
        assertThat(remaining(lot2)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM finished_vehicle WHERE vin = ?",
                                Integer.class,
                                VIN_1))
                .isZero();
    }

    @Test
    void cancelDoesNotChangeLots() {
        productionListener.onProductionCancelled(
                envelope("production.cancelled", Map.of("orderId", 42L, "productionId", 100L)));

        assertThat(remaining(lot1)).isEqualTo(3);
        assertThat(remaining(lot2)).isEqualTo(5);
        assertThat(consumeHistories()).isZero();
    }

    private void assertLotsWithinStock() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT p.id FROM part p WHERE p.available + p.reserved <"
                                        + " (SELECT COALESCE(SUM(l.remaining_qty), 0) FROM part_lot l"
                                        + " WHERE l.part_id = p.id)"))
                .isEmpty();
    }

    private EventEnvelope received(long purchaseId, long partId, int qty) {
        return envelope(
                "purchase.received",
                Map.of("purchaseId", purchaseId, "partId", partId, "qty", qty));
    }

    private EventEnvelope completed(long productionId, String vin) {
        return envelope(
                "production.completed",
                Map.of("orderId", 42L, "productionId", productionId, "productId", 1L, "vin", vin));
    }

    private EventEnvelope envelope(String name, Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID(),
                name,
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(payload));
    }

    /** 부품을 생산에 쓴 재고 이력 수 (생산 건 기준, CONSUME). */
    private int consumeHistories() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_history WHERE type = 'CONSUME'", Integer.class);
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

package com.factoryflow.inventory.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.test.container.TestContainersConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** 의장 공정에서 찍은 LOT 으로 재고를 뺀다 (docs/specs/part-lot-scan.md "3. 재고 차감", #170). */
@SpringBootTest
@Import(TestContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class PartLotScanConsumptionTest {

    static final String VIN = "KMH000017TU000100";
    static final String LOT_1 = "LOT-261001-000001";
    static final String LOT_2 = "LOT-261002-000002";

    @Autowired ProductionEventListener productionListener;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WebApplicationContext context;

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
        jdbc.update("UPDATE storage_slot SET finished_vehicle_id = NULL");
        // 엔진: 초기 재고 LOT 10. 타이어: 초기 재고 LOT 2 + LOT1 3 + LOT2 5. 생산 건 100 은 엔진 1, 타이어 4 를 잡아 두었다
        engine = insertPart("ENG-T", 9, 1);
        tire = insertPart("TIRE-T", 6, 4);
        engineInit = insertLot(engine, "LOT-INIT-" + engine, null, 10);
        tireInit = insertLot(tire, "LOT-INIT-" + tire, null, 2);
        lot1 = insertLot(tire, LOT_1, 1L, 3);
        lot2 = insertLot(tire, LOT_2, 2L, 5);
        insertReservation(100L, engine, 1);
        insertReservation(100L, tire, 4);
    }

    @Test
    void scannedLotIsUsedAndTheOthersFollowReceiptOrder() {
        // 타이어는 LOT2 를 찍었다. 엔진은 안 찍어서 지금처럼 초기 재고 LOT 부터
        productionListener.onProductionCompleted(completed(scan(tire, LOT_2)));

        assertThat(remainingByLot())
                .containsExactly(
                        tuple(engineInit, 9), tuple(tireInit, 2), tuple(lot1, 3), tuple(lot2, 1));
    }

    @Test
    void unusableScansFallBackToReceiptOrderWithAWarning(CapturedOutput output) {
        // 엔진에 타이어 LOT, 타이어에 없는 LOT, 차에 없는 부품의 LOT 을 찍었다
        productionListener.onProductionCompleted(
                completed(
                        scan(engine, LOT_1),
                        scan(tire, "LOT-999999-999999"),
                        scan(999_999L, LOT_2)));

        assertThat(remainingByLot())
                .containsExactly(
                        tuple(engineInit, 9), tuple(tireInit, 0), tuple(lot1, 1), tuple(lot2, 5));
        assertThat(output)
                .contains("SCANNED_LOT_REJECTED", "reason=OTHER_PART")
                .contains("reason=NOT_FOUND")
                .contains("reason=NOT_IN_VEHICLE");
        assertThat(finishedVehicles()).isOne();
    }

    @Test
    void scannedLotWithoutEnoughLeftFallsBackToReceiptOrder(CapturedOutput output) {
        // LOT1 은 3개 남아 타이어 4 를 다 줄 수 없다 → 그 부품은 입고 순 (초기 재고 2 + LOT1 2)
        productionListener.onProductionCompleted(completed(scan(tire, LOT_1)));

        assertThat(remainingByLot())
                .containsExactly(
                        tuple(engineInit, 9), tuple(tireInit, 0), tuple(lot1, 1), tuple(lot2, 5));
        assertThat(output).contains("SCANNED_LOT_REJECTED", "reason=NOT_ENOUGH");
    }

    @Test
    void completedWithoutScannedLotsWorksAsBefore() {
        productionListener.onProductionCompleted(
                envelope(
                        Map.of(
                                "orderId", 42L,
                                "productionId", 100L,
                                "productId", 1L,
                                "vin", VIN)));

        assertThat(remainingByLot())
                .containsExactly(
                        tuple(engineInit, 9), tuple(tireInit, 0), tuple(lot1, 1), tuple(lot2, 5));
    }

    @Test
    void scannedLotShowsUpOnlyAsLowerStockOfThatLot() throws Exception {
        // inventory 는 LOT 재고만 바뀌고 차 ↔ LOT 기록은 남기지 않는다. 계보는 mes 가 찍은 LOT 으로 갖는다 (#176)
        productionListener.onProductionCompleted(completed(scan(tire, LOT_2)));
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        mockMvc.perform(get("/inventory/parts/{partId}/lots", tire))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lots[?(@.lotNo == '%s')].remainingQty", LOT_2).value(1))
                .andExpect(
                        jsonPath("$.data.lots[?(@.lotNo == '%s')].remainingQty", LOT_1).value(3));
        mockMvc.perform(get("/inventory/finished-vehicles/{vin}/part-lots", VIN))
                .andExpect(status().isNotFound());
    }

    private static Map<String, Object> scan(long partId, String lotNo) {
        return Map.of("partId", partId, "lotNo", lotNo);
    }

    @SafeVarargs
    private EventEnvelope completed(Map<String, Object>... scans) {
        return envelope(
                Map.of(
                        "orderId", 42L,
                        "productionId", 100L,
                        "productId", 1L,
                        "vin", VIN,
                        "scannedLots", List.of(scans)));
    }

    private EventEnvelope envelope(Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "production.completed",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(payload));
    }

    /** LOT 마다 남은 수량, id 순. */
    private List<Tuple> remainingByLot() {
        return jdbc.queryForList("SELECT id, remaining_qty FROM part_lot ORDER BY id").stream()
                .map(l -> tuple(l.get("id"), l.get("remaining_qty")))
                .toList();
    }

    private int finishedVehicles() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM finished_vehicle WHERE vin = ?", Integer.class, VIN);
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

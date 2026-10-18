package com.factoryflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 재고 조정 (docs/specs/stock-adjustment.md). */
@SpringBootTest
@Import(TestContainersConfig.class)
class StockAdjustmentTest {

    static final String WORKER = "6c1e2f3a-0000-0000-0000-000000000001";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM stock_adjustment");
        jdbc.update("DELETE FROM part_lot_usage");
        jdbc.update("DELETE FROM part_lot");
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM part_reservation");
        jdbc.update("DELETE FROM part");
        jdbc.update("DELETE FROM outbox");
    }

    @Test
    void increaseAddsAvailableHistoryAndAnAdjustmentLot() throws Exception {
        long tire = insertPart("TIRE-18", 3, 0);

        String body =
                adjust(tire, "{\"qty\":5,\"reason\":\"실사에서 더 나옴\"}")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.partId").value(tire))
                        .andExpect(jsonPath("$.data.qty").value(5))
                        .andExpect(jsonPath("$.data.reason").value("실사에서 더 나옴"))
                        .andExpect(jsonPath("$.data.available").value(8))
                        .andExpect(jsonPath("$.data.reserved").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        long adjustmentId = JsonPath.<Number>read(body, "$.data.adjustmentId").longValue();
        String lotNo = String.format("LOT-ADJ-%06d", adjustmentId);
        assertThat(JsonPath.<String>read(body, "$.data.lotNo")).isEqualTo(lotNo);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT part_id, qty, reason, adjusted_by FROM stock_adjustment"))
                .containsEntry("part_id", tire)
                .containsEntry("qty", 5)
                .containsEntry("reason", "실사에서 더 나옴")
                .containsEntry("adjusted_by", WORKER);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT part_id, qty, type, ref_type, ref_id FROM stock_history"))
                .containsEntry("qty", 5)
                .containsEntry("type", "ADJUST_IN")
                .containsEntry("ref_type", "ADJUSTMENT")
                .containsEntry("ref_id", adjustmentId);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT part_id, purchase_id, received_qty, remaining_qty FROM part_lot"
                                        + " WHERE lot_no = ?",
                                lotNo))
                .containsEntry("part_id", tire)
                .containsEntry("purchase_id", null)
                .containsEntry("received_qty", 5)
                .containsEntry("remaining_qty", 5);
    }

    @Test
    void increaseFillsWaitingLinesInOrderAndStopsAtTheFirstThatDoesNotFit() throws Exception {
        long tire = insertPart("TIRE-18", 0, 0);
        insertReservation(101L, tire, 4, "WAITING");
        insertReservation(102L, tire, 3, "WAITING");

        adjust(tire, "{\"qty\":5,\"reason\":\"실사에서 더 나옴\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.available").value(1))
                .andExpect(jsonPath("$.data.reserved").value(4));

        // 입고와 같다: 먼저 기다린 101 이 받고, 102 는 1 < 3 이라 거기서 멈춘다
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM part_reservation ORDER BY production_id",
                                String.class))
                .containsExactly("RESERVED", "WAITING");
        assertThat(
                        jdbc.queryForList(
                                "SELECT payload FROM outbox WHERE event_name = 'inventory.reserved'",
                                String.class))
                .singleElement()
                .satisfies(
                        payload ->
                                assertThat(
                                                JsonPath.<Number>read(
                                                                payload, "$.payload.productionId")
                                                        .longValue())
                                        .isEqualTo(101L));
    }

    @Test
    void decreaseTakesLotsInReceiptOrder() throws Exception {
        long tire = insertPart("TIRE-18", 5, 0);
        long older = insertLot(tire, "LOT-261001-000001", 2, "2026-10-01 09:00:00");
        long newer = insertLot(tire, "LOT-261005-000002", 3, "2026-10-05 09:00:00");

        adjust(tire, "{\"qty\":-3,\"reason\":\"운반 중 파손\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.qty").value(-3))
                .andExpect(jsonPath("$.data.available").value(2))
                .andExpect(jsonPath("$.data.lotNo").doesNotExist());

        assertThat(remaining(older)).isZero();
        assertThat(remaining(newer)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT qty, type, ref_type FROM stock_history"))
                .containsEntry("qty", 3)
                .containsEntry("type", "ADJUST_OUT")
                .containsEntry("ref_type", "ADJUSTMENT");
        assertThat(jdbc.queryForObject("SELECT qty FROM stock_adjustment", Integer.class))
                .isEqualTo(-3);
    }

    @Test
    void decreaseCannotTouchReservedStock() throws Exception {
        long tire = insertPart("TIRE-18", 2, 6);
        long lot = insertLot(tire, "LOT-261001-000001", 8, "2026-10-01 09:00:00");

        adjust(tire, "{\"qty\":-3,\"reason\":\"운반 중 파손\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_AVAILABLE"));

        assertThat(jdbc.queryForMap("SELECT available, reserved FROM part WHERE id = ?", tire))
                .containsAllEntriesOf(Map.of("available", 2, "reserved", 6));
        assertThat(remaining(lot)).isEqualTo(8);
        assertNothingRecorded();
    }

    @Test
    void lotsShorterThanStockArePartLotShortageAndNothingChanges() throws Exception {
        // 실물(available 5)보다 LOT 남은 수량(3)이 적은 어긋난 데이터: 줄이다 LOT 이 모자라면 롤백한다
        long tire = insertPart("TIRE-18", 5, 0);
        long lot = insertLot(tire, "LOT-261001-000001", 3, "2026-10-01 09:00:00");

        adjust(tire, "{\"qty\":-4,\"reason\":\"운반 중 파손\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PART_LOT_SHORTAGE"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT available FROM part WHERE id = ?", Integer.class, tire))
                .isEqualTo(5);
        assertThat(remaining(lot)).isEqualTo(3);
        assertNothingRecorded();
    }

    @Test
    void concurrentAdjustmentsOfOnePartAreEachAppliedOrRejected() throws Exception {
        // 같은 부품을 동시에 조정하면 늦게 저장한 쪽은 409 PART_CONFLICT 이고, 수량에는 성공한 조정만 들어간다
        long tire = insertPart("TIRE-18", 10, 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int succeeded = 0;
        try {
            for (int round = 0; round < 5; round++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> statuses = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    statuses.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return adjust(tire, "{\"qty\":1,\"reason\":\"실사\"}")
                                                .andReturn()
                                                .getResponse()
                                                .getStatus();
                                    }));
                }
                start.countDown();
                for (Future<Integer> status : statuses) {
                    int code = status.get(10, TimeUnit.SECONDS);
                    assertThat(code).isIn(201, 409);
                    if (code == 201) {
                        succeeded++;
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded).isPositive();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available FROM part WHERE id = ?", Integer.class, tire))
                .isEqualTo(10 + succeeded);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_adjustment", Integer.class))
                .isEqualTo(succeeded);
    }

    @Test
    void invalidAdjustmentIs400() throws Exception {
        long tire = insertPart("TIRE-18", 5, 0);

        adjust(tire, "{\"qty\":0,\"reason\":\"실사\"}").andExpect(status().isBadRequest());
        // 한 번에 ±1,000,000 까지. 넘으면 수량 계산이 넘칠 수 있다 (Integer.MIN_VALUE 의 부호를 바꾸면 그대로 음수)
        adjust(tire, "{\"qty\":-2147483648,\"reason\":\"실사\"}").andExpect(status().isBadRequest());
        adjust(tire, "{\"qty\":-1000001,\"reason\":\"실사\"}").andExpect(status().isBadRequest());
        adjust(tire, "{\"qty\":1000001,\"reason\":\"실사\"}").andExpect(status().isBadRequest());
        adjust(tire, "{\"reason\":\"실사\"}").andExpect(status().isBadRequest());
        adjust(tire, "{\"qty\":1}").andExpect(status().isBadRequest());
        adjust(tire, "{\"qty\":1,\"reason\":\" \"}").andExpect(status().isBadRequest());
        adjust(tire, "{\"qty\":1,\"reason\":\"" + "가".repeat(256) + "\"}")
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/inventory/parts/{id}/adjustments", tire)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"qty\":1,\"reason\":\"실사\"}"))
                .andExpect(status().isBadRequest());
        adjust(0L, "{\"qty\":1,\"reason\":\"실사\"}").andExpect(status().isBadRequest());

        assertNothingRecorded();
    }

    @Test
    void unknownOrDeletedPartIs404() throws Exception {
        long deleted = insertPart("TIRE-18", 5, 0);
        jdbc.update("UPDATE part SET is_deleted = TRUE WHERE id = ?", deleted);

        adjust(deleted, "{\"qty\":1,\"reason\":\"실사\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PART_NOT_FOUND"));
        adjust(deleted + 100, "{\"qty\":1,\"reason\":\"실사\"}").andExpect(status().isNotFound());
        assertNothingRecorded();
    }

    @Test
    void adjustmentLotNumberIsAValidLotNumber() throws Exception {
        mockMvc.perform(get("/inventory/part-lots/{lotNo}/vehicles", "LOT-ADJ-000012"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PART_LOT_NOT_FOUND"));
    }

    @Test
    void adjustmentApiIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/inventory/parts/{partId}/adjustments'].post.summary")
                                .value("재고 조정"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/inventory/parts/{partId}/adjustments'].post.responses['409']")
                                .exists());
    }

    private ResultActions adjust(long partId, String body) throws Exception {
        return mockMvc.perform(
                post("/inventory/parts/{id}/adjustments", partId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", WORKER)
                        .content(body));
    }

    private void assertNothingRecorded() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_adjustment", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history", Integer.class))
                .isZero();
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

    private void insertReservation(long productionId, long partId, int qty, String status) {
        jdbc.update(
                "INSERT INTO part_reservation (production_id, part_id, qty, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                productionId,
                partId,
                qty,
                status);
    }

    private long insertLot(long partId, String lotNo, int remaining, String receivedAt) {
        jdbc.update(
                "INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty,"
                        + " received_at, created_at, updated_at) VALUES (?, ?, NULL, ?, ?, ?,"
                        + " NOW(6), NOW(6))",
                partId,
                lotNo,
                remaining,
                remaining,
                Timestamp.valueOf(receivedAt));
        return jdbc.queryForObject("SELECT id FROM part_lot WHERE lot_no = ?", Long.class, lotNo);
    }
}

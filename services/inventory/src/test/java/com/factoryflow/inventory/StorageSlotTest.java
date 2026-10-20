package com.factoryflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.inventory.event.listener.payload.ProductionCompleted;
import com.factoryflow.inventory.repository.StorageSlotRepository;
import com.factoryflow.inventory.service.FinishedVehicleService;
import com.factoryflow.test.container.TestContainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** 완성차 보관 위치: 입고 때 자동 배정, 출고 때 비움, 자리 대기, 자리 현황 (docs/specs/finished-vehicle-location.md). */
@SpringBootTest
@Import(TestContainersConfig.class)
class StorageSlotTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired FinishedVehicleService finishedVehicleService;
    @Autowired StorageSlotRepository storageSlotRepository;

    MockMvc mockMvc;
    long part;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("UPDATE storage_slot SET finished_vehicle_id = NULL");
        jdbc.update("DELETE FROM part_lot_usage");
        jdbc.update("DELETE FROM part_lot");
        jdbc.update("DELETE FROM finished_vehicle");
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM part_reservation");
        jdbc.update("DELETE FROM part");
        jdbc.update("DELETE FROM outbox");
        // 차마다 부품 1개를 잡아 둔다 (완성 때 사용 처리)
        jdbc.update(
                "INSERT INTO part (code, name, unit, available, reserved, created_at, updated_at)"
                        + " VALUES ('SLOT-T', 'SLOT-T', 'EA', 0, 100, NOW(6), NOW(6))");
        part = jdbc.queryForObject("SELECT id FROM part WHERE code = 'SLOT-T'", Long.class);
        jdbc.update(
                "INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty,"
                        + " received_at, created_at, updated_at)"
                        + " VALUES (?, 'LOT-INIT-SLOT', NULL, 100, 100, NOW(6), NOW(6), NOW(6))",
                part);
    }

    @Test
    void stockedVehiclesTakeTheFirstFreeSlotsInCodeOrder() {
        stock(1);
        stock(2);

        assertThat(slotOf(vin(1))).isEqualTo("A-01");
        assertThat(slotOf(vin(2))).isEqualTo("A-02");
        assertThat(vehicleIn("A-01")).isEqualTo(vin(1));
        assertThat(vehicleIn("A-02")).isEqualTo(vin(2));
    }

    @Test
    void aFreedSlotIsReusedBeforeLaterOnes() {
        stock(1);
        stock(2);
        finishedVehicleService.ship(vin(1));

        stock(3);

        assertThat(slotOf(vin(3))).isEqualTo("A-01");
    }

    @Test
    void whenTheYardIsFullTheVehicleIsStockedWithoutASlot() {
        fillAllSlotsWithOtherCars();

        stock(1);

        assertThat(jdbc.queryForObject("SELECT status FROM finished_vehicle", String.class))
                .isEqualTo("IN_STOCK");
        assertThat(slotOf(vin(1))).isNull();
    }

    @Test
    void shippingFreesTheSlotAndMovesTheOldestWaitingVehicleIn() throws Exception {
        stock(1);
        fillAllSlotsWithOtherCars();
        stock(2);
        stock(3);

        mockMvc.perform(post("/inventory/finished-vehicles/{vin}/ship", vin(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slotCode").value("A-01"));

        // 출고한 차는 어디서 나갔는지 남는다
        assertThat(slotOf(vin(1))).isEqualTo("A-01");
        assertThat(vehicleIn("A-01")).isEqualTo(vin(2));
        assertThat(slotOf(vin(2))).isEqualTo("A-01");
        assertThat(slotOf(vin(3))).isNull();
    }

    @Test
    void theSameCompletionTwiceTakesOneSlot() {
        stock(1);
        stock(1);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM storage_slot WHERE finished_vehicle_id IS NOT NULL",
                                Integer.class))
                .isOne();
    }

    @Test
    @Transactional
    void twoCarsPickingTheSameSlotConflict() {
        // 이 차가 빈 자리 A-01 을 읽은 뒤 다른 차가 먼저 그 자리를 잡았다 → 저장 때 충돌, 리스너는 재시도해 다음 자리를 고른다
        storageSlotRepository.findByCode("A-01");
        jdbc.update("UPDATE storage_slot SET version = version + 1 WHERE code = 'A-01'");

        stock(1);

        assertThatThrownBy(() -> storageSlotRepository.flush())
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void listAndSlotViewsShowWhereEachVehicleIs() throws Exception {
        stock(1);
        fillAllSlotsWithOtherCars();
        stock(2);

        mockMvc.perform(get("/inventory/finished-vehicles").param("status", "IN_STOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].vin").value(vin(2)))
                .andExpect(jsonPath("$.data.items[0].slotCode").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].slotCode").value("A-01"));
        mockMvc.perform(get("/inventory/storage-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(40))
                .andExpect(jsonPath("$.data.items[0].slotCode").value("A-01"))
                .andExpect(jsonPath("$.data.items[0].zone").value("A"))
                .andExpect(jsonPath("$.data.items[0].vin").value(vin(1)))
                .andExpect(jsonPath("$.data.items[39].slotCode").value("B-20"))
                .andExpect(jsonPath("$.data.total").value(40))
                .andExpect(jsonPath("$.data.occupied").value(40))
                .andExpect(jsonPath("$.data.waitingVehicles").value(1));
    }

    @Test
    void slotApiIsDocumented() throws Exception {
        String path = "$.paths['/inventory/storage-slots'].get";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(path + ".summary").value("완성차 자리 현황"))
                .andExpect(jsonPath(path + ".responses['200']").exists());
    }

    /** 생산 건 n 의 차가 완성돼 창고에 들어온다 (production.completed). */
    private void stock(long n) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM part_reservation WHERE production_id = ?",
                        Integer.class,
                        n)
                == 0) {
            jdbc.update(
                    "INSERT INTO part_reservation (production_id, part_id, qty, status, created_at,"
                            + " updated_at) VALUES (?, ?, 1, 'RESERVED', NOW(6), NOW(6))",
                    n,
                    part);
        }
        finishedVehicleService.stock(new ProductionCompleted(42L, n, 1L, vin(n)));
    }

    /** 남은 빈 자리를 모두 다른 차(이 테스트 밖의 완성차)로 채운다. */
    private void fillAllSlotsWithOtherCars() {
        jdbc.update(
                "UPDATE storage_slot SET finished_vehicle_id = -id WHERE finished_vehicle_id IS NULL");
    }

    private static String vin(long n) {
        return String.format("KMH000017TU%06d", n);
    }

    private String slotOf(String vin) {
        return jdbc.queryForObject(
                "SELECT slot_code FROM finished_vehicle WHERE vin = ?", String.class, vin);
    }

    private String vehicleIn(String code) {
        return jdbc.queryForObject(
                "SELECT f.vin FROM storage_slot s JOIN finished_vehicle f ON f.id = s.finished_vehicle_id"
                        + " WHERE s.code = ?",
                String.class,
                code);
    }
}

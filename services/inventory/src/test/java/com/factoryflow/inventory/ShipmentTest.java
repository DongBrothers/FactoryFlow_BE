package com.factoryflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.web.TraceIdFilter;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.FinishedVehicleRepository;
import com.factoryflow.inventory.service.FinishedVehicleService;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** 완성차 출고와 shipment.completed (docs/specs/shipment.md). */
@SpringBootTest
@Import(TestContainersConfig.class)
class ShipmentTest {

    static final String VIN = "KMH000017TU000012";
    static final String TRACE_ID = "trace-shipment-1";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired FinishedVehicleService finishedVehicleService;
    @Autowired FinishedVehicleRepository finishedVehicleRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 요청 헤더의 traceId 를 이벤트가 이어 쓰는지 보려고 실제 앱처럼 TraceIdFilter 를 건다
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context).addFilters(new TraceIdFilter()).build();
        jdbc.update("DELETE FROM finished_vehicle");
        jdbc.update("DELETE FROM outbox");
        insertFinishedVehicle(VIN, 12L, false);
    }

    @Test
    void shipMarksTheVehicleShippedAndPublishesShipmentCompleted() throws Exception {
        ship(VIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vin").value(VIN))
                .andExpect(jsonPath("$.data.productId").value(1))
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.shippedAt").exists())
                .andExpect(jsonPath("$.data.productionId").doesNotExist())
                .andExpect(jsonPath("$.data.orderId").doesNotExist());

        assertThat(vehicle(VIN)).containsEntry("status", "SHIPPED");
        assertThat(vehicle(VIN).get("shipped_at")).isNotNull();
        assertThat(outbox())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(number(e, "$.payload.orderId")).isEqualTo(42L);
                            assertThat(number(e, "$.payload.productionId")).isEqualTo(12L);
                            assertThat(number(e, "$.payload.productId")).isEqualTo(1L);
                            assertThat(JsonPath.<String>read(e, "$.payload.vin")).isEqualTo(VIN);
                            assertThat(JsonPath.<String>read(e, "$.traceId")).isEqualTo(TRACE_ID);
                        });
    }

    @Test
    void shipIsRejectedWhenItCannotBeShipped() throws Exception {
        ship("KMH000017TU00001").andExpect(status().isBadRequest());
        ship("KMH000017TU00001O").andExpect(status().isBadRequest());
        ship("KMH000017TU000099")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FINISHED_VEHICLE_NOT_FOUND"));
        insertFinishedVehicle("KMH000017TU000013", 13L, true);
        ship("KMH000017TU000013")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FINISHED_VEHICLE_NOT_FOUND"));
        assertThat(outbox()).isEmpty();

        ship(VIN).andExpect(status().isOk());
        ship(VIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FINISHED_VEHICLE_ALREADY_SHIPPED"));
        assertThat(outbox()).hasSize(1);
    }

    @Test
    @Transactional
    void concurrentShipIsFinishedVehicleConflict() {
        // 이 요청이 완성차를 읽은 뒤 다른 출고 요청이 먼저 바꿨다
        finishedVehicleRepository.findByVinAndIsDeletedFalse(VIN);
        jdbc.update("UPDATE finished_vehicle SET version = version + 1 WHERE vin = ?", VIN);

        assertThatThrownBy(() -> finishedVehicleService.ship(VIN))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(InventoryErrorCode.FINISHED_VEHICLE_CONFLICT);
    }

    @Test
    void shipApiIsDocumented() throws Exception {
        String path = "$.paths['/inventory/finished-vehicles/{vin}/ship'].post";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(path + ".summary").value("완성차 출고"))
                .andExpect(jsonPath(path + ".responses['200']").exists())
                .andExpect(jsonPath(path + ".responses['400']").exists())
                .andExpect(jsonPath(path + ".responses['404']").exists())
                .andExpect(jsonPath(path + ".responses['409']").exists());
    }

    private ResultActions ship(String vin) throws Exception {
        return mockMvc.perform(
                post("/inventory/finished-vehicles/{vin}/ship", vin)
                        .header("X-Trace-Id", TRACE_ID));
    }

    private Map<String, Object> vehicle(String vin) {
        return jdbc.queryForMap(
                "SELECT status, shipped_at FROM finished_vehicle WHERE vin = ?", vin);
    }

    private List<String> outbox() {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = 'shipment.completed' ORDER BY id",
                String.class);
    }

    private static long number(String json, String path) {
        return JsonPath.<Number>read(json, path).longValue();
    }

    private void insertFinishedVehicle(String vin, long productionId, boolean deleted) {
        jdbc.update(
                "INSERT INTO finished_vehicle (vin, production_id, order_id, product_id, status,"
                        + " created_at, updated_at, is_deleted)"
                        + " VALUES (?, ?, 42, 1, 'IN_STOCK', NOW(6), NOW(6), ?)",
                vin,
                productionId,
                deleted);
    }
}

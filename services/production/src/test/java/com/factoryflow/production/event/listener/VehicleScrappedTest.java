package com.factoryflow.production.event.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.production.service.ProductionService;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** mes 가 불량으로 차를 폐기하면 생산 건을 폐기하고 같은 주문으로 대체 차를 만든다 (docs/specs/defect-scrap.md, #182). */
@SpringBootTest
@Import(TestContainersConfig.class)
class VehicleScrappedTest {

    static final long ORDER = 42L;

    @Autowired MesEventListener listener;
    @Autowired ProductionService productionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    long car;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM production");
        jdbc.update("DELETE FROM bom");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        car = insertProduct("CAR-A");
        insertBom(car, 1001, 1);
        insertBom(car, 1011, 4);
    }

    @Test
    void scrappedCarIsClosedAndAReplacementIsPlannedWithTheNextSeq() {
        long first = startedProduction(1);
        startedProduction(2);

        listener.onVehicleScrapped(scrapped(first));

        assertThat(statusOf(first)).isEqualTo("SCRAPPED");
        String scrapped = outbox("production.scrapped").get(0);
        assertThat(JsonPath.<Integer>read(scrapped, "$.payload.orderId")).isEqualTo((int) ORDER);
        assertThat(((Number) JsonPath.read(scrapped, "$.payload.productionId")).longValue())
                .isEqualTo(first);
        // 같은 주문·제품의 다음 차 번호(3)로 대체 차를 만들고 지금 BOM 으로 부품을 요청한다
        Map<String, Object> replacement =
                jdbc.queryForMap(
                        "SELECT id, seq, status FROM production WHERE order_id = ? AND seq = 3",
                        ORDER);
        assertThat(replacement).containsEntry("status", "PLANNED");
        long replacementId = ((Number) replacement.get("id")).longValue();
        String request = outbox("production.material-requested").get(0);
        assertThat(((Number) JsonPath.read(request, "$.payload.productionId")).longValue())
                .isEqualTo(replacementId);
        assertThat(JsonPath.<List<Map<String, Object>>>read(request, "$.payload.parts"))
                .containsExactlyInAnyOrder(
                        Map.of("partId", 1001, "qty", 1), Map.of("partId", 1011, "qty", 4));
        assertThat(outbox("production.status-changed"))
                .extracting(p -> JsonPath.<String>read(p, "$.payload.status"))
                .containsExactly("SCRAPPED", "PLANNED");
    }

    @Test
    void sameScrapTwiceMakesOneReplacement() {
        long production = startedProduction(1);
        EventEnvelope event = scrapped(production);

        listener.onVehicleScrapped(event);
        listener.onVehicleScrapped(event);
        listener.onVehicleScrapped(scrapped(production));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM production WHERE order_id = ?",
                                Integer.class,
                                ORDER))
                .isEqualTo(2);
        assertThat(outbox("production.scrapped")).hasSize(1);
    }

    @Test
    void scrapArrivingBeforeTheStartStillScrapsTheReleasedCar() {
        // 큐가 달라 폐기 알림이 시작 알림보다 먼저 올 수 있다
        long production = releasedProduction(1);

        listener.onVehicleScrapped(scrapped(production));

        assertThat(statusOf(production)).isEqualTo("SCRAPPED");
        assertThat(outbox("production.scrapped")).hasSize(1);
    }

    @Test
    void replacementIsCancelledWhenTheProductCanNoLongerBeMade() {
        long production = startedProduction(1);
        jdbc.update("UPDATE product SET is_deleted = TRUE WHERE id = ?", car);

        listener.onVehicleScrapped(scrapped(production));

        assertThat(statusOf(production)).isEqualTo("SCRAPPED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM production WHERE order_id = ? AND seq = 2",
                                String.class,
                                ORDER))
                .isEqualTo("CANCELLED");
        assertThat(outbox("production.material-requested")).isEmpty();
    }

    @Test
    void scrappedCarRejectsTheOrderCancelWithItsStatus() {
        long production = startedProduction(1);
        listener.onVehicleScrapped(scrapped(production));
        jdbc.update("DELETE FROM outbox");

        productionService.cancelAll(ORDER);

        // 폐기된 차는 취소할 수 없다고 SCRAPPED 로 답해 order 의 취소 요청 회차가 끝나게 하고, 발행 전인 대체 차는 취소한다
        assertThat(statusOf(production)).isEqualTo("SCRAPPED");
        assertThat(outbox("production.cancel-rejected"))
                .extracting(p -> JsonPath.<String>read(p, "$.payload.status"))
                .containsExactly("SCRAPPED");
        assertThat(outbox("production.cancelled")).hasSize(1);
    }

    /** 부품이 다 잡혀 발행했고 mes 가 첫 공정을 시작한 차. */
    private long startedProduction(int seq) {
        long production = releasedProduction(seq);
        productionService.startProduction(production);
        jdbc.update("DELETE FROM outbox");
        return production;
    }

    private long releasedProduction(int seq) {
        jdbc.update(
                "INSERT INTO production (order_id, product_id, seq, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'PLANNED', NOW(6), NOW(6))",
                ORDER,
                car,
                seq);
        long production =
                jdbc.queryForObject(
                        "SELECT id FROM production WHERE order_id = ? AND seq = ?",
                        Long.class,
                        ORDER,
                        seq);
        productionService.markReserved(production);
        productionService.release(production);
        jdbc.update("DELETE FROM outbox");
        return production;
    }

    private EventEnvelope scrapped(long production) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "mes.vehicle-scrapped",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                objectMapper.valueToTree(
                        Map.of(
                                "productionId",
                                production,
                                "workOrderId",
                                12L,
                                "process",
                                "BODY",
                                "defectReason",
                                "용접 불량")));
    }

    private String statusOf(long production) {
        return jdbc.queryForObject(
                "SELECT status FROM production WHERE id = ?", String.class, production);
    }

    private List<String> outbox(String eventName) {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = ? ORDER BY id",
                String.class,
                eventName);
    }

    private long insertProduct(String code) {
        jdbc.update(
                "INSERT INTO product (code, name, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                code,
                code);
        return jdbc.queryForObject("SELECT id FROM product WHERE code = ?", Long.class, code);
    }

    private void insertBom(long productId, long partId, int qtyPerUnit) {
        jdbc.update(
                "INSERT INTO bom (product_id, part_id, qty_per_unit, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NOW(6), NOW(6))",
                productId,
                partId,
                qtyPerUnit);
    }
}

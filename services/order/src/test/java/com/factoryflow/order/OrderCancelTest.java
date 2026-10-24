package com.factoryflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.event.EventEnvelope;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.event.listener.ProductionEventListener;
import com.factoryflow.order.exception.OrderErrorCode;
import com.factoryflow.order.repository.CustomerOrderRepository;
import com.factoryflow.order.service.OrderService;
import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** 주문 취소 요청, 답 반영, 주문 상세 조회 (docs/specs/order-cancel.md). */
@SpringBootTest
@Import(TestContainersConfig.class)
class OrderCancelTest {

    static final long CAR_A = 1L;
    static final long CAR_B = 2L;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionEventListener listener;
    @Autowired OrderService orderService;
    @Autowired CustomerOrderRepository customerOrderRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitTemplate rabbitTemplate;

    MockMvc mockMvc;
    long orderId;
    String orderNo;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM order_vehicle");
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM customer_order");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        // 주문: A 2대(생산 중, 접수) + B 1대(접수)
        orderNo = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO customer_order (order_no, customer_id, status, created_at, updated_at)"
                        + " VALUES (?, 7, 'IN_PRODUCTION', NOW(6), NOW(6))",
                orderNo);
        orderId =
                jdbc.queryForObject(
                        "SELECT id FROM customer_order WHERE order_no = ?", Long.class, orderNo);
        insertItem(CAR_A, 2, "IN_PRODUCTION");
        insertItem(CAR_B, 1, "RECEIVED");
        insertVehicle(CAR_A, 101L, "IN_PRODUCTION");
        insertVehicle(CAR_A, 102L, "RECEIVED");
        insertVehicle(CAR_B, 201L, "RECEIVED");
    }

    @Test
    void cancelRequestMarksEveryVehicleAndPublishesCancelRequested() throws Exception {
        cancel(orderNo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.status").value("CANCEL_REQUESTED"))
                .andExpect(
                        jsonPath("$.data.items[0].vehicles[0].status").value("CANCEL_REQUESTED"));

        assertThat(vehicleStatuses()).containsOnly("CANCEL_REQUESTED");
        assertThat(itemStatus(CAR_A)).isEqualTo("CANCEL_REQUESTED");
        assertThat(orderStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(cancelRequestedAt()).isNotNull();
        assertThat(outbox("order.cancel-requested"))
                .singleElement()
                .satisfies(p -> assertThat(number(p, "$.payload.orderId")).isEqualTo(orderId));
    }

    @Test
    void cancelIsRejectedWhenItCannotBeRequested() throws Exception {
        cancel(UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        cancel("not-a-uuid").andExpect(status().isBadRequest());

        jdbc.update("UPDATE order_vehicle SET status = 'COMPLETED' WHERE production_id = 101");
        cancel(orderNo)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CANNOT_BE_CANCELLED"));

        jdbc.update("UPDATE order_vehicle SET status = 'CANCEL_REQUESTED'");
        cancel(orderNo)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_IN_PROGRESS"));

        jdbc.update("UPDATE order_vehicle SET status = 'CANCELLED'");
        cancel(orderNo)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));

        // 차량을 다 모른다 (생산 계획 중)
        jdbc.update("UPDATE order_vehicle SET status = 'RECEIVED'");
        jdbc.update("DELETE FROM order_vehicle WHERE production_id = 201");
        cancel(orderNo)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_READY"));
        assertThat(outbox("order.cancel-requested")).isEmpty();
    }

    @Test
    @Transactional
    void concurrentChangeIsOrderConflict() {
        // 이 요청이 주문을 읽은 뒤 다른 처리(차량 상태 반영)가 먼저 바꿨다
        customerOrderRepository.findById(orderId);
        jdbc.update("UPDATE customer_order SET version = version + 1 WHERE id = ?", orderId);

        assertThatThrownBy(() -> orderService.cancel(orderNo))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_CONFLICT);
    }

    @Test
    void answersFinishTheRoundAndOrderCancelledIsSentOnce() {
        orderService.cancel(orderNo);

        listener.onCancelled(cancelled(102L));
        listener.onCancelled(cancelled(201L));
        assertThat(outbox("order.cancelled")).isEmpty();
        listener.onCancelRejected(cancelRejected(101L, "IN_PROGRESS"));

        assertThat(vehicle(101L)).containsEntry("status", "IN_PRODUCTION");
        assertThat(vehicle(102L)).containsEntry("status", "CANCELLED");
        assertThat(vehicle(102L).get("cancelled_at")).isNotNull();
        assertThat(itemStatus(CAR_A)).isEqualTo("IN_PRODUCTION");
        assertThat(itemStatus(CAR_B)).isEqualTo("CANCELLED");
        assertThat(orderStatus()).isEqualTo("IN_PRODUCTION");
        assertThat(outbox("order.cancelled"))
                .singleElement()
                .satisfies(p -> assertThat(number(p, "$.payload.orderId")).isEqualTo(orderId));
        assertThat(cancelRequestedAt()).isNull();

        // 늦게 온 같은 답은 회차를 다시 끝내지 않는다
        listener.onCancelled(cancelled(102L));
        assertThat(outbox("order.cancelled")).hasSize(1);
    }

    @Test
    void allRejectedSendsNoOrderCancelled() {
        orderService.cancel(orderNo);

        listener.onCancelRejected(cancelRejected(101L, "IN_PROGRESS"));
        listener.onCancelRejected(cancelRejected(102L, "IN_PROGRESS"));
        listener.onCancelRejected(cancelRejected(201L, "COMPLETED"));

        assertThat(vehicle(102L)).containsEntry("status", "IN_PRODUCTION");
        assertThat(vehicle(201L)).containsEntry("status", "COMPLETED");
        assertThat(outbox("order.cancelled")).isEmpty();
        assertThat(cancelRequestedAt()).isNull();
    }

    @Test
    void scrappedVehicleIsNotAskedToCancel() throws Exception {
        // 101 은 폐기됐고 대체 차 103 이 들어왔다 (docs/specs/defect-scrap.md)
        jdbc.update("UPDATE order_vehicle SET status = 'SCRAPPED' WHERE production_id = 101");
        insertVehicle(CAR_A, 103L, "RECEIVED");

        // 응답의 차량 목록에도 폐기 차량은 없다 (A 2대: 102, 103)
        cancel(orderNo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].vehicles.length()").value(2))
                .andExpect(
                        jsonPath("$.data.items[0].vehicles[*].status")
                                .value(everyItem(is("CANCEL_REQUESTED"))));

        assertThat(vehicle(101L)).containsEntry("status", "SCRAPPED");
        assertThat(vehicle(103L)).containsEntry("status", "CANCEL_REQUESTED");
    }

    @Test
    void scrappedVehicleWithoutItsReplacementYetIsNotReady() throws Exception {
        jdbc.update("UPDATE order_vehicle SET status = 'SCRAPPED' WHERE production_id = 101");

        cancel(orderNo)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_READY"));
    }

    @Test
    void scrapAnsweredAsRejectionFinishesTheRound() {
        // 취소 요청과 폐기가 엇갈렸다: production 은 폐기된 차를 SCRAPPED 로 거절한다
        orderService.cancel(orderNo);

        listener.onCancelRejected(cancelRejected(101L, "SCRAPPED"));
        listener.onCancelled(cancelled(102L));
        listener.onCancelled(cancelled(201L));

        assertThat(vehicle(101L)).containsEntry("status", "SCRAPPED");
        assertThat(cancelRequestedAt()).isNull();
        assertThat(outbox("order.cancelled")).hasSize(1);
    }

    @Test
    void allCancelledCancelsTheOrder() {
        orderService.cancel(orderNo);

        for (long production : List.of(101L, 102L, 201L)) {
            listener.onCancelled(cancelled(production));
        }

        assertThat(orderStatus()).isEqualTo("CANCELLED");
        assertThat(outbox("order.cancelled")).hasSize(1);
    }

    @Test
    void receivesAnswersFromRabbitMq() {
        orderService.cancel(orderNo);

        send("production.cancelled", cancelled(201L));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(vehicle(201L)).containsEntry("status", "CANCELLED"));
    }

    @Test
    void orderDetailShowsItemsAndVehiclesWithoutInternalIds() throws Exception {
        jdbc.update(
                "UPDATE order_vehicle SET status = 'COMPLETED', vin = 'KMH000017TU000101'"
                        + " WHERE production_id = 101");

        mockMvc.perform(get("/orders/{orderNo}", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.customerId").value(7))
                .andExpect(jsonPath("$.data.orderId").doesNotExist())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].productCode").value("CAR-1"))
                .andExpect(jsonPath("$.data.items[0].qty").value(2))
                .andExpect(jsonPath("$.data.items[0].vehicles[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].vehicles[0].vin").value("KMH000017TU000101"))
                .andExpect(jsonPath("$.data.items[0].vehicles[0].productionId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].vehicles[1].status").value("RECEIVED"));

        mockMvc.perform(get("/orders/{orderNo}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(get("/orders/{orderNo}", "not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void orderDetailLeavesOutScrappedVehicles() throws Exception {
        // 불량으로 폐기된 차 대신 대체 차가 같은 품목에 들어온다 (docs/specs/defect-scrap.md)
        jdbc.update("UPDATE order_vehicle SET status = 'SCRAPPED' WHERE production_id = 201");
        insertVehicle(CAR_B, 202L, "RECEIVED");

        mockMvc.perform(get("/orders/{orderNo}", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[1].qty").value(1))
                .andExpect(jsonPath("$.data.items[1].vehicles.length()").value(1))
                .andExpect(jsonPath("$.data.items[1].vehicles[0].status").value("RECEIVED"));
    }

    @Test
    void cancelAndDetailApisAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/orders/{orderNo}'].get.summary").value("주문 상세"))
                .andExpect(
                        jsonPath("$.paths['/orders/{orderNo}/cancel'].post.summary")
                                .value("주문 취소 요청"))
                .andExpect(
                        jsonPath("$.paths['/orders/{orderNo}/cancel'].post.responses['409']")
                                .exists());
    }

    private ResultActions cancel(String orderNo) throws Exception {
        return mockMvc.perform(post("/orders/{orderNo}/cancel", orderNo));
    }

    private EventEnvelope cancelled(long productionId) {
        return envelope(
                "production.cancelled", Map.of("orderId", orderId, "productionId", productionId));
    }

    private EventEnvelope cancelRejected(long productionId, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("productionId", productionId);
        payload.put("reason", "ALREADY_STARTED");
        payload.put("status", status);
        return envelope("production.cancel-rejected", payload);
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

    private void send(String routingKey, EventEnvelope event) {
        rabbitTemplate.send(
                "factoryflow.events",
                routingKey,
                MessageBuilder.withBody(
                                objectMapper
                                        .writeValueAsString(event)
                                        .getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(event.eventId().toString())
                        .build());
    }

    private void insertItem(long productId, int qty, String status) {
        jdbc.update(
                "INSERT INTO order_item (order_id, product_id, product_code, product_name, qty,"
                        + " status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                orderId,
                productId,
                "CAR-" + productId,
                "차 " + productId,
                qty,
                status);
    }

    private void insertVehicle(long productId, long productionId, String status) {
        jdbc.update(
                "INSERT INTO order_vehicle (order_id, order_item_id, production_id, status,"
                        + " created_at, updated_at)"
                        + " SELECT order_id, id, ?, ?, NOW(6), NOW(6) FROM order_item"
                        + " WHERE order_id = ? AND product_id = ?",
                productionId,
                status,
                orderId,
                productId);
    }

    private Map<String, Object> vehicle(long productionId) {
        return jdbc.queryForMap(
                "SELECT status, cancelled_at FROM order_vehicle WHERE production_id = ?",
                productionId);
    }

    private List<String> vehicleStatuses() {
        return jdbc.queryForList("SELECT status FROM order_vehicle", String.class);
    }

    private String itemStatus(long productId) {
        return jdbc.queryForObject(
                "SELECT status FROM order_item WHERE order_id = ? AND product_id = ?",
                String.class,
                orderId,
                productId);
    }

    private String orderStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM customer_order WHERE id = ?", String.class, orderId);
    }

    private Object cancelRequestedAt() {
        return jdbc.queryForMap(
                        "SELECT cancel_requested_at FROM customer_order WHERE id = ?", orderId)
                .get("cancel_requested_at");
    }

    private List<String> outbox(String eventName) {
        return jdbc.queryForList(
                "SELECT payload FROM outbox WHERE event_name = ? ORDER BY id",
                String.class,
                eventName);
    }

    private static long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}

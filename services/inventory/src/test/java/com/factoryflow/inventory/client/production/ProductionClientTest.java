package com.factoryflow.inventory.client.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.client.production.dto.request.HoldMaterialCandidateRequest;
import com.factoryflow.inventory.client.production.dto.request.HoldMaterialRequest;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** production 에 찜한 부품을 거둘 차를 묻는 client (docs/specs/stock-adjustment.md "찜한 부품 거둬들이기"). */
class ProductionClientTest {

    static final String URL = "http://production/internal/productions/material-holds";
    static final HoldMaterialRequest REQUEST =
            new HoldMaterialRequest(1012L, 1, List.of(new HoldMaterialCandidateRequest(9L, 4)));

    MockRestServiceServer server;
    ProductionClient productionClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://production");
        server = MockRestServiceServer.bindTo(builder).build();
        productionClient = ProductionClientConfig.create(builder.build());
    }

    @Test
    void returnsTheHeldProductionIds() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        content()
                                .json(
                                        """
                                        {"partId":1012,"neededQty":1,
                                         "candidates":[{"productionId":9,"qty":4}]}
                                        """))
                .andRespond(
                        withSuccess(
                                """
                                {"data":{"heldProductionIds":[9]},"status":200,"message":"OK"}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThat(productionClient.holdForMaterial(REQUEST)).containsExactly(9L);
        server.verify();
    }

    @Test
    void conflictMeansNotEnoughToReclaim() {
        server.expect(requestTo(URL))
                .andRespond(
                        withStatus(HttpStatus.CONFLICT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"code\":\"NOT_ENOUGH_UNRELEASED\"}"));

        assertError(InventoryErrorCode.INSUFFICIENT_AVAILABLE);
    }

    @Test
    void otherConflictIsProductionUnavailable() {
        // 동시 변경 충돌 같은 다른 409 는 재고 부족이 아니다 (PR #172 리뷰)
        server.expect(requestTo(URL))
                .andRespond(
                        withStatus(HttpStatus.CONFLICT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"code\":\"PRODUCTION_CONFLICT\",\"message\":\"x\"}"));

        assertError(InventoryErrorCode.PRODUCTION_UNAVAILABLE);
    }

    @Test
    void unreadableConflictBodyIsProductionUnavailable() {
        // 본문을 읽지 못한 409 는 재고 부족이라고 단정하지 않는다 (PR #172 리뷰)
        server.expect(requestTo(URL))
                .andRespond(
                        withStatus(HttpStatus.CONFLICT)
                                .contentType(MediaType.TEXT_PLAIN)
                                .body("conflict"));

        assertError(InventoryErrorCode.PRODUCTION_UNAVAILABLE);
    }

    @Test
    void missingDataIsProductionUnavailable() {
        server.expect(requestTo(URL))
                .andRespond(
                        withSuccess(
                                """
                                {"status":200,"message":"OK"}
                                """,
                                MediaType.APPLICATION_JSON));

        assertError(InventoryErrorCode.PRODUCTION_UNAVAILABLE);
    }

    @Test
    void serverErrorIsProductionUnavailable() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertError(InventoryErrorCode.PRODUCTION_UNAVAILABLE);
    }

    @Test
    void timeoutIsProductionUnavailable() {
        server.expect(requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertError(InventoryErrorCode.PRODUCTION_UNAVAILABLE);
    }

    private void assertError(InventoryErrorCode expected) {
        assertThatThrownBy(() -> productionClient.holdForMaterial(REQUEST))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }
}

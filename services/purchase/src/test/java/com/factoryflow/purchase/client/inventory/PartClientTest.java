package com.factoryflow.purchase.client.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.purchase.client.inventory.dto.response.PartResponse;
import com.factoryflow.purchase.exception.PurchaseErrorCode;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** inventory 부품 확인 client (docs/specs/purchase-manual.md "2", "3"). */
class PartClientTest {

    MockRestServiceServer server;
    PartClient partClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory");
        server = MockRestServiceServer.bindTo(builder).build();
        partClient = PartClientConfig.create(builder.build());
    }

    @Test
    void mapsPartsFromResponse() {
        server.expect(requestTo("http://inventory/internal/parts?ids=1012"))
                .andRespond(
                        withSuccess(
                                """
                                {"data":[{"id":1012,"code":"TIRE-18","name":"타이어 18인치"}],
                                 "status":200,"message":"OK"}
                                """,
                                MediaType.APPLICATION_JSON));

        List<PartResponse> parts = partClient.findParts(List.of(1012L));

        assertThat(parts).containsExactly(new PartResponse(1012L, "TIRE-18", "타이어 18인치"));
        server.verify();
    }

    @Test
    void emptyDataIsEmptyList() {
        server.expect(requestTo("http://inventory/internal/parts?ids=9"))
                .andRespond(
                        withSuccess(
                                """
                                {"data":[],"status":200,"message":"OK"}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThat(partClient.findParts(List.of(9L))).isEmpty();
    }

    @Test
    void missingDataIsInventoryUnavailable() {
        server.expect(requestTo("http://inventory/internal/parts?ids=1"))
                .andRespond(
                        withSuccess(
                                """
                                {"status":200,"message":"OK"}
                                """,
                                MediaType.APPLICATION_JSON));

        assertUnavailable();
    }

    @Test
    void emptyBodyIsInventoryUnavailable() {
        server.expect(requestTo("http://inventory/internal/parts?ids=1")).andRespond(withSuccess());

        assertUnavailable();
    }

    @Test
    void serverErrorIsInventoryUnavailable() {
        server.expect(requestTo("http://inventory/internal/parts?ids=1"))
                .andRespond(withServerError());

        assertUnavailable();
    }

    @Test
    void timeoutIsInventoryUnavailable() {
        server.expect(requestTo("http://inventory/internal/parts?ids=1"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> partClient.findParts(List.of(1L)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(PurchaseErrorCode.INVENTORY_UNAVAILABLE);
    }
}

package com.factoryflow.order.client.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.client.production.dto.response.ProductResponse;
import com.factoryflow.order.exception.OrderErrorCode;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ProductClientTest {

    MockRestServiceServer server;
    ProductClient productClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://production");
        server = MockRestServiceServer.bindTo(builder).build();
        productClient = ProductClientConfig.create(builder.build());
    }

    @Test
    void mapsProductsFromResponse() {
        server.expect(requestTo("http://production/internal/products?ids=1&ids=2"))
                .andRespond(
                        withSuccess(
                                """
                                {"data":[{"id":1,"code":"CAR-A","name":"소형차 A","orderable":true}],
                                 "status":200,"message":"OK"}
                                """,
                                MediaType.APPLICATION_JSON));

        List<ProductResponse> products = productClient.findProducts(List.of(1L, 2L));

        assertThat(products).containsExactly(new ProductResponse(1L, "CAR-A", "소형차 A", true));
        server.verify();
    }

    @Test
    void emptyDataIsEmptyList() {
        server.expect(requestTo("http://production/internal/products?ids=9"))
                .andRespond(
                        withSuccess(
                                """
                                {"data":[],"status":200,"message":"OK"}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThat(productClient.findProducts(List.of(9L))).isEmpty();
    }

    @Test
    void missingDataIsProductionUnavailable() {
        server.expect(requestTo("http://production/internal/products?ids=1"))
                .andRespond(
                        withSuccess(
                                """
                                {"status":200,"message":"OK"}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> productClient.findProducts(List.of(1L)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.PRODUCTION_UNAVAILABLE);
    }

    @Test
    void emptyBodyIsProductionUnavailable() {
        server.expect(requestTo("http://production/internal/products?ids=1"))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> productClient.findProducts(List.of(1L)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.PRODUCTION_UNAVAILABLE);
    }

    @Test
    void serverErrorIsProductionUnavailable() {
        server.expect(requestTo("http://production/internal/products?ids=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> productClient.findProducts(List.of(1L)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.PRODUCTION_UNAVAILABLE);
    }

    @Test
    void timeoutIsProductionUnavailable() {
        server.expect(requestTo("http://production/internal/products?ids=1"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> productClient.findProducts(List.of(1L)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.PRODUCTION_UNAVAILABLE);
    }
}

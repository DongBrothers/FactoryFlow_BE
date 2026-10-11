package com.factoryflow.order.client;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.exception.OrderErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** production 내부 API 호출. 실패와 시간 초과는 모두 PRODUCTION_UNAVAILABLE(503) 이다. */
@Slf4j
@Component
public class ProductClient {

    private final RestClient restClient;

    public ProductClient(RestClient productRestClient) {
        this.restClient = productRestClient;
    }

    /** 있는 제품만 돌려준다. 없는 id 는 결과에서 빠진다. */
    public List<ProductInfo> findProducts(Collection<Long> ids) {
        try {
            ProductsResponse response =
                    restClient
                            .get()
                            .uri(
                                    uri ->
                                            uri.path("/internal/products")
                                                    .queryParam("ids", ids.toArray())
                                                    .build())
                            .retrieve()
                            .body(ProductsResponse.class);
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (RestClientException e) {
            log.warn("[Order] production 제품 조회 실패: {}", e.getMessage());
            throw new CustomException(OrderErrorCode.PRODUCTION_UNAVAILABLE);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductsResponse(List<ProductInfo> data) {}
}

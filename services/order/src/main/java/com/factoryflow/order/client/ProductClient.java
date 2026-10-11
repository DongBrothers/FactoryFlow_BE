package com.factoryflow.order.client;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.exception.OrderErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** production 내부 API (HTTP Interface). 구현은 {@link ProductClientConfig} 가 만든다. */
@HttpExchange("/internal/products")
public interface ProductClient {

    Logger log = LoggerFactory.getLogger(ProductClient.class);

    @GetExchange
    ProductsResponse getProducts(@RequestParam("ids") Collection<Long> ids);

    /** 있는 제품만 돌려준다. 실패와 시간 초과는 모두 PRODUCTION_UNAVAILABLE(503) 이다. */
    default List<ProductInfo> findProducts(Collection<Long> ids) {
        try {
            ProductsResponse response = getProducts(ids);
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (RestClientException e) {
            log.warn("[Order] production 제품 조회 실패: {}", e.getMessage());
            throw new CustomException(OrderErrorCode.PRODUCTION_UNAVAILABLE);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductsResponse(List<ProductInfo> data) {}
}

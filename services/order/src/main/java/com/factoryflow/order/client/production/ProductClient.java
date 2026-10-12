package com.factoryflow.order.client.production;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.client.production.dto.response.ProductResponse;
import com.factoryflow.order.client.production.dto.response.ProductsResponse;
import com.factoryflow.order.exception.OrderErrorCode;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange("/internal/products")
public interface ProductClient {

    Logger log = LoggerFactory.getLogger(ProductClient.class);

    @GetExchange
    ProductsResponse getProducts(@RequestParam("ids") Collection<Long> ids);

    /**
     * 있는 제품만 돌려준다. 실패, 시간 초과, 본문이나 data 가 없는 비정상 응답은 모두 PRODUCTION_UNAVAILABLE(503) 이다. 정상 응답의 빈
     * data 만 빈 목록이다.
     */
    default List<ProductResponse> findProducts(Collection<Long> ids) {
        ProductsResponse response;
        try {
            response = getProducts(ids);
        } catch (RestClientException e) {
            log.warn("[Order] production 제품 조회 실패: {}", e.getMessage());
            throw new CustomException(OrderErrorCode.PRODUCTION_UNAVAILABLE);
        }
        if (response == null || response.data() == null) {
            log.warn("[Order] production 제품 조회 응답에 data 가 없음");
            throw new CustomException(OrderErrorCode.PRODUCTION_UNAVAILABLE);
        }
        return response.data();
    }
}

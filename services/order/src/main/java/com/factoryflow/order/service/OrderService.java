package com.factoryflow.order.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.client.production.ProductClient;
import com.factoryflow.order.client.production.dto.response.ProductResponse;
import com.factoryflow.order.dto.request.CreateOrderItemRequest;
import com.factoryflow.order.dto.request.CreateOrderRequest;
import com.factoryflow.order.dto.response.CreateOrderResponse;
import com.factoryflow.order.exception.OrderErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final ProductClient productClient;
    private final OrderWriter orderWriter;

    /**
     * 제품을 확인한 뒤 주문을 저장하고 주문 번호를 담은 응답을 돌려준다. production 조회는 트랜잭션 밖에서 해서 외부 호출 동안 DB 커넥션을 잡지 않고, 저장은
     * OrderWriter 가 한 트랜잭션으로 한다.
     */
    public CreateOrderResponse create(Long customerId, CreateOrderRequest request) {
        List<Long> productIds =
                request.items().stream().map(CreateOrderItemRequest::productId).toList();
        Map<Long, ProductResponse> products =
                productClient.findProducts(productIds).stream()
                        .collect(Collectors.toMap(ProductResponse::id, Function.identity()));
        for (Long productId : productIds) {
            ProductResponse product = products.get(productId);
            if (product == null) {
                throw new CustomException(OrderErrorCode.PRODUCT_NOT_FOUND);
            }
            if (!product.orderable()) {
                throw new CustomException(OrderErrorCode.BOM_NOT_FOUND);
            }
        }
        return orderWriter.save(customerId, request, products);
    }
}

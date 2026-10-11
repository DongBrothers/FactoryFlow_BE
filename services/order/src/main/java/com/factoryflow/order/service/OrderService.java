package com.factoryflow.order.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.client.production.ProductClient;
import com.factoryflow.order.client.production.dto.response.ProductResponse;
import com.factoryflow.order.domain.CustomerOrder;
import com.factoryflow.order.domain.OrderItem;
import com.factoryflow.order.dto.request.CreateOrderRequest;
import com.factoryflow.order.dto.response.CreateOrderResponse;
import com.factoryflow.order.event.publisher.OrderCreated;
import com.factoryflow.order.exception.OrderErrorCode;
import com.factoryflow.order.repository.CustomerOrderRepository;
import com.factoryflow.order.repository.OrderItemRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private final ProductClient productClient;
    private final CustomerOrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final EventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public OrderService(
            ProductClient productClient,
            CustomerOrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            EventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.productClient = productClient;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 제품을 확인한 뒤 주문, 품목, order.created 를 한 트랜잭션에 저장하고 주문 번호를 담은 응답을 돌려준다. production 조회는 트랜잭션 밖에서 해서
     * 외부 호출 동안 DB 커넥션을 잡지 않는다.
     */
    public CreateOrderResponse create(Long customerId, CreateOrderRequest request) {
        List<Long> productIds =
                request.items().stream().map(CreateOrderRequest.Item::productId).toList();
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
        return transactionTemplate.execute(status -> save(customerId, request, products));
    }

    private CreateOrderResponse save(
            Long customerId, CreateOrderRequest request, Map<Long, ProductResponse> products) {
        CustomerOrder order = orderRepository.save(CustomerOrder.receive(customerId));
        List<OrderItem> items =
                request.items().stream()
                        .map(
                                item -> {
                                    ProductResponse product = products.get(item.productId());
                                    return OrderItem.receive(
                                            order.getId(),
                                            product.id(),
                                            product.code(),
                                            product.name(),
                                            item.qty());
                                })
                        .toList();
        orderItemRepository.saveAll(items);
        eventPublisher.publish(
                new OrderCreated(
                        order.getId(),
                        items.stream()
                                .map(i -> new OrderCreated.Product(i.getProductId(), i.getQty()))
                                .toList()));
        return CreateOrderResponse.from(order);
    }
}

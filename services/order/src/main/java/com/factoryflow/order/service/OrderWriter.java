package com.factoryflow.order.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.order.client.production.dto.response.ProductResponse;
import com.factoryflow.order.domain.CustomerOrder;
import com.factoryflow.order.domain.OrderItem;
import com.factoryflow.order.dto.request.CreateOrderItemRequest;
import com.factoryflow.order.dto.request.CreateOrderRequest;
import com.factoryflow.order.dto.response.CreateOrderResponse;
import com.factoryflow.order.event.publisher.payload.OrderCreated;
import com.factoryflow.order.event.publisher.payload.OrderCreatedProduct;
import com.factoryflow.order.repository.CustomerOrderRepository;
import com.factoryflow.order.repository.OrderItemRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 주문, 품목, order.created(outbox) 를 한 트랜잭션에 저장한다. 외부 호출은 OrderService 가 트랜잭션 밖에서 끝낸 뒤 부른다. */
@Component
@RequiredArgsConstructor
public class OrderWriter {

    private final CustomerOrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final EventPublisher eventPublisher;

    @Transactional
    public CreateOrderResponse save(
            Long customerId, CreateOrderRequest request, Map<Long, ProductResponse> products) {
        CustomerOrder order = orderRepository.save(CustomerOrder.receive(customerId));
        List<OrderItem> items =
                request.items().stream()
                        .map(item -> toOrderItem(order, item, products.get(item.productId())))
                        .toList();
        orderItemRepository.saveAll(items);
        eventPublisher.publish(
                new OrderCreated(
                        order.getId(),
                        items.stream()
                                .map(i -> new OrderCreatedProduct(i.getProductId(), i.getQty()))
                                .toList()));
        return CreateOrderResponse.from(order);
    }

    private static OrderItem toOrderItem(
            CustomerOrder order, CreateOrderItemRequest item, ProductResponse product) {
        return OrderItem.receive(
                order.getId(), product.id(), product.code(), product.name(), item.qty());
    }
}

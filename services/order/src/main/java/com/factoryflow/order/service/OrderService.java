package com.factoryflow.order.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.client.production.ProductClient;
import com.factoryflow.order.client.production.dto.response.ProductResponse;
import com.factoryflow.order.domain.CustomerOrder;
import com.factoryflow.order.domain.OrderItem;
import com.factoryflow.order.domain.OrderStatus;
import com.factoryflow.order.domain.OrderVehicle;
import com.factoryflow.order.dto.request.CreateOrderItemRequest;
import com.factoryflow.order.dto.request.CreateOrderRequest;
import com.factoryflow.order.dto.response.CreateOrderResponse;
import com.factoryflow.order.dto.response.OrderDetailResponse;
import com.factoryflow.order.dto.response.OrderItemResponse;
import com.factoryflow.order.dto.response.OrderListResponse;
import com.factoryflow.order.dto.response.OrderSummaryResponse;
import com.factoryflow.order.event.publisher.payload.OrderCancelRequested;
import com.factoryflow.order.exception.OrderErrorCode;
import com.factoryflow.order.repository.CustomerOrderRepository;
import com.factoryflow.order.repository.CustomerRepository;
import com.factoryflow.order.repository.OrderItemRepository;
import com.factoryflow.order.repository.OrderVehicleRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final ProductClient productClient;
    private final OrderWriter orderWriter;
    private final CustomerRepository customerRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderVehicleRepository orderVehicleRepository;
    private final EventPublisher eventPublisher;

    /**
     * 고객과 제품을 확인한 뒤 주문을 저장하고 주문 번호를 담은 응답을 돌려준다. 등록되지 않은 고객이면 production 을 부르지 않고
     * CUSTOMER_NOT_FOUND (docs/specs/customer.md). production 조회는 트랜잭션 밖에서 해서 외부 호출 동안 DB 커넥션을 잡지
     * 않고, 저장은 OrderWriter 가 한 트랜잭션으로 한다.
     */
    public CreateOrderResponse create(CreateOrderRequest request) {
        if (!isRegisteredCustomer(request.customerId())) {
            throw new CustomException(OrderErrorCode.CUSTOMER_NOT_FOUND);
        }
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
        return orderWriter.save(request, products);
    }

    /**
     * 주문 목록, 최신 먼저 size 개. status 가 있으면 그 상태만. lastOrderNo 가 있으면 그 주문보다 오래된 것부터 (밖으로는 주문 번호만 쓰고 안에서
     * id 로 바꾼다). 다음 페이지가 있는지 알려고 하나 더 읽는다 (docs/specs/list-apis.md).
     */
    @Transactional(readOnly = true)
    public OrderListResponse findOrders(String status, String lastOrderNo, int size) {
        Long beforeId = lastOrderNo == null ? Long.MAX_VALUE : findOrderByNo(lastOrderNo).getId();
        List<OrderSummaryResponse> rows =
                findOrdersBefore(status, beforeId, size + 1).stream()
                        .map(OrderSummaryResponse::from)
                        .toList();
        return OrderListResponse.of(rows, size);
    }

    /** 주문 상세: 품목과 알려진 차량. 내부 id 는 내보내지 않는다. */
    @Transactional(readOnly = true)
    public OrderDetailResponse findOrder(String orderNo) {
        CustomerOrder order = findOrderByNo(orderNo);
        return detail(order, findItemsOf(order.getId()), findVehiclesOf(order.getId()));
    }

    /**
     * 주문 취소 요청: 취소되지 않은 차량을 모두 CANCEL_REQUESTED 로 바꾸고 품목·주문을 다시 요약한 뒤 order.cancel-requested. 최종
     * 판단은 production 이 차량마다 한다 (docs/specs/order-cancel.md).
     */
    @Transactional
    public OrderDetailResponse cancel(String orderNo) {
        CustomerOrder order = findOrderByNo(orderNo);
        List<OrderItem> items = findItemsOf(order.getId());
        List<OrderVehicle> vehicles = findVehiclesOf(order.getId());
        requireCancellable(items, vehicles);
        vehicles.forEach(OrderVehicle::requestCancel);
        summarize(order, items, vehicles);
        order.startCancelRound(LocalDateTime.now());
        flushOrConflict(order.getId());
        eventPublisher.publish(new OrderCancelRequested(order.getId()));
        return detail(order, items, vehicles);
    }

    /** 차량을 모두 알고(생산 계획이 끝남), 완료·출고 차량이 없고, 진행 중인 취소 요청이 없고, 취소할 차량이 있어야 한다. */
    /** 폐기된 차량은 대체 차가 대신하므로 빼고 본다 (docs/specs/defect-scrap.md). */
    private static void requireCancellable(List<OrderItem> items, List<OrderVehicle> all) {
        List<OrderVehicle> vehicles = all.stream().filter(v -> !v.isScrapped()).toList();
        int expected = items.stream().mapToInt(OrderItem::getQty).sum();
        if (vehicles.size() < expected) {
            throw new CustomException(OrderErrorCode.ORDER_NOT_READY);
        }
        if (hasVehicleIn(vehicles, OrderStatus.COMPLETED, OrderStatus.SHIPPED)) {
            throw new CustomException(OrderErrorCode.ORDER_CANNOT_BE_CANCELLED);
        }
        if (hasVehicleIn(vehicles, OrderStatus.CANCEL_REQUESTED)) {
            throw new CustomException(OrderErrorCode.CANCEL_IN_PROGRESS);
        }
        if (vehicles.stream().allMatch(v -> v.getStatus() == OrderStatus.CANCELLED)) {
            throw new CustomException(OrderErrorCode.ORDER_ALREADY_CANCELLED);
        }
    }

    private static boolean hasVehicleIn(List<OrderVehicle> vehicles, OrderStatus... statuses) {
        List<OrderStatus> wanted = List.of(statuses);
        return vehicles.stream().anyMatch(v -> wanted.contains(v.getStatus()));
    }

    /** 차량을 품목별로 한 번만 묶는다. 묶음 안에서는 원래 순서(생산 건 id 순서)를 지킨다. */
    private static Map<Long, List<OrderVehicle>> byItem(List<OrderVehicle> vehicles) {
        return vehicles.stream().collect(Collectors.groupingBy(OrderVehicle::getOrderItemId));
    }

    private static List<OrderVehicle> vehiclesOf(
            OrderItem item, Map<Long, List<OrderVehicle>> vehiclesByItem) {
        return vehiclesByItem.getOrDefault(item.getId(), List.of());
    }

    /** 차량들로 품목 상태를, 품목들로 주문 상태를 다시 계산한다. */
    private static void summarize(
            CustomerOrder order, List<OrderItem> items, List<OrderVehicle> vehicles) {
        Map<Long, List<OrderVehicle>> vehiclesByItem = byItem(vehicles);
        for (OrderItem item : items) {
            item.refreshStatus(
                    vehiclesOf(item, vehiclesByItem).stream()
                            .map(OrderVehicle::getStatus)
                            .toList());
        }
        order.refreshStatus(items.stream().map(OrderItem::getStatus).toList());
    }

    private static OrderDetailResponse detail(
            CustomerOrder order, List<OrderItem> items, List<OrderVehicle> vehicles) {
        Map<Long, List<OrderVehicle>> vehiclesByItem = byItem(vehicles);
        return OrderDetailResponse.of(
                order,
                items.stream()
                        .map(item -> OrderItemResponse.of(item, vehiclesOf(item, vehiclesByItem)))
                        .toList());
    }

    /** 이벤트를 보내기 전에 저장해 version 을 확인한다. 다른 처리가 먼저 바꿨으면 ORDER_CONFLICT. */
    private void flushOrConflict(Long orderId) {
        try {
            customerOrderRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.info("다른 작업이 먼저 바꿔 거절: orderId={}", orderId);
            throw new CustomException(OrderErrorCode.ORDER_CONFLICT);
        }
    }

    private CustomerOrder findOrderByNo(String orderNo) {
        return customerOrderRepository
                .findByOrderNo(orderNo)
                .orElseThrow(() -> new CustomException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    private List<CustomerOrder> findOrdersBefore(String status, Long beforeId, int limit) {
        if (status == null) {
            return customerOrderRepository.findAllByIdLessThanOrderByIdDesc(
                    beforeId, Limit.of(limit));
        }
        return customerOrderRepository.findAllByStatusAndIdLessThanOrderByIdDesc(
                OrderStatus.valueOf(status), beforeId, Limit.of(limit));
    }

    private List<OrderItem> findItemsOf(Long orderId) {
        return orderItemRepository.findAllByOrderIdOrderByIdAsc(orderId);
    }

    private List<OrderVehicle> findVehiclesOf(Long orderId) {
        return orderVehicleRepository.findAllByOrderIdOrderByProductionIdAsc(orderId);
    }

    private boolean isRegisteredCustomer(Long customerId) {
        return customerRepository.existsById(customerId);
    }
}

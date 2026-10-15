package com.factoryflow.order.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.domain.CustomerOrder;
import com.factoryflow.order.domain.OrderItem;
import com.factoryflow.order.domain.OrderStatus;
import com.factoryflow.order.domain.OrderVehicle;
import com.factoryflow.order.exception.OrderErrorCode;
import com.factoryflow.order.repository.CustomerOrderRepository;
import com.factoryflow.order.repository.OrderItemRepository;
import com.factoryflow.order.repository.OrderVehicleRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderVehicleService {

    private final OrderVehicleRepository orderVehicleRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerOrderRepository customerOrderRepository;

    /**
     * 생산 건 상태를 그 차량에 반영하고, 바뀌었으면 같은 트랜잭션에서 품목·주문 상태를 다시 계산한다. 처음 보는 생산 건이면 품목에 차량을 만든다
     * (docs/specs/order-production-status.md).
     */
    @Transactional
    public void applyProductionStatus(
            Long orderId, Long productionId, Long productId, String productionStatus) {
        OrderStatus next = vehicleStatusOf(productionStatus);
        OrderItem item = findItem(orderId, productId);
        OrderVehicle vehicle =
                findVehicle(productionId).orElseGet(() -> addVehicle(item, productionId));
        if (!vehicle.applyProductionStatus(next)) {
            log.info(
                    "차량 상태를 바꾸지 않음: orderId={} productionId={} vehicle={} production={}",
                    orderId,
                    productionId,
                    vehicle.getStatus(),
                    productionStatus);
            return;
        }
        refreshStatuses(item);
    }

    /** 품목 수량보다 많은 생산 건은 받지 않는다. 정상 흐름에서는 생기지 않는다. */
    private OrderVehicle addVehicle(OrderItem item, Long productionId) {
        if (findVehicles(item.getId()).size() >= item.getQty()) {
            throw new CustomException(OrderErrorCode.ORDER_VEHICLE_OVERFLOW);
        }
        return orderVehicleRepository.save(
                OrderVehicle.receive(item.getOrderId(), item.getId(), productionId));
    }

    /** 차량들로 품목 상태를, 품목들로 주문 상태를 다시 계산한다. */
    private void refreshStatuses(OrderItem item) {
        item.refreshStatus(
                findVehicles(item.getId()).stream().map(OrderVehicle::getStatus).toList());
        findOrder(item.getOrderId())
                .refreshStatus(
                        findItems(item.getOrderId()).stream().map(OrderItem::getStatus).toList());
    }

    private Optional<OrderVehicle> findVehicle(Long productionId) {
        return orderVehicleRepository.findByProductionId(productionId);
    }

    private List<OrderVehicle> findVehicles(Long orderItemId) {
        return orderVehicleRepository.findAllByOrderItemId(orderItemId);
    }

    private OrderItem findItem(Long orderId, Long productId) {
        return orderItemRepository
                .findByOrderIdAndProductId(orderId, productId)
                .orElseThrow(() -> new CustomException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    private List<OrderItem> findItems(Long orderId) {
        return orderItemRepository.findAllByOrderId(orderId);
    }

    private CustomerOrder findOrder(Long orderId) {
        return customerOrderRepository
                .findById(orderId)
                .orElseThrow(() -> new CustomException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    /** 생산 건 상태를 차량 상태로 바꾼다 (docs/specs/saga-order-fulfillment.md "상태 매핑"). */
    private static OrderStatus vehicleStatusOf(String productionStatus) {
        return switch (productionStatus) {
            case "PLANNED" -> OrderStatus.RECEIVED;
            case "WAITING_MATERIAL" -> OrderStatus.WAITING_MATERIAL;
            case "READY", "IN_PROGRESS" -> OrderStatus.IN_PRODUCTION;
            case "COMPLETED" -> OrderStatus.COMPLETED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            case "FAILED" -> OrderStatus.FAILED;
            default -> throw new CustomException(OrderErrorCode.UNKNOWN_PRODUCTION_STATUS);
        };
    }
}

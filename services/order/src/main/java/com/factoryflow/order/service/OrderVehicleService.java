package com.factoryflow.order.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.domain.CustomerOrder;
import com.factoryflow.order.domain.OrderItem;
import com.factoryflow.order.domain.OrderStatus;
import com.factoryflow.order.domain.OrderVehicle;
import com.factoryflow.order.event.publisher.payload.OrderCancelled;
import com.factoryflow.order.exception.OrderErrorCode;
import com.factoryflow.order.repository.CustomerOrderRepository;
import com.factoryflow.order.repository.OrderItemRepository;
import com.factoryflow.order.repository.OrderVehicleRepository;
import java.time.LocalDateTime;
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
    private final EventPublisher eventPublisher;

    /**
     * 생산 건 상태를 그 차량에 반영하고, 바뀌었으면 같은 트랜잭션에서 품목·주문 상태를 다시 계산한다. 처음 보는 생산 건이면 품목에 차량을 만든다
     * (docs/specs/order-production-status.md).
     */
    @Transactional
    public void applyProductionStatus(
            Long orderId, Long productionId, Long productId, String productionStatus) {
        OrderStatus next = vehicleStatusOf(productionStatus);
        OrderItem item = findItem(orderId, productId);
        OrderVehicle vehicle = vehicleOf(item, productionId);
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

    /**
     * production.completed: 차량을 COMPLETED 로 바꾸고 VIN 을 기록한 뒤, 바뀌었으면 같은 트랜잭션에서 품목·주문 상태를 다시 계산한다. 끝난
     * 차량은 그대로 둔다 (docs/specs/production-completion.md).
     */
    @Transactional
    public void complete(Long orderId, Long productionId, Long productId, String vin) {
        OrderItem item = findItem(orderId, productId);
        OrderVehicle vehicle = vehicleOf(item, productionId);
        if (!vehicle.complete(vin)) {
            log.info(
                    "차량을 완료로 바꾸지 않음: orderId={} productionId={} vehicle={}",
                    orderId,
                    productionId,
                    vehicle.getStatus());
            return;
        }
        refreshStatuses(item);
    }

    /**
     * shipment.completed: 차량을 SHIPPED 로 바꾸고, 바뀌었으면 같은 트랜잭션에서 품목·주문 상태를 다시 계산한다. 처음 보는 생산 건이면 품목에
     * 차량을 만든다. 출고·취소된 차량은 그대로 둔다 (docs/specs/shipment.md).
     */
    @Transactional
    public void ship(Long orderId, Long productionId, Long productId, String vin) {
        OrderItem item = findItem(orderId, productId);
        OrderVehicle vehicle = vehicleOf(item, productionId);
        if (!vehicle.ship(vin)) {
            logNotShipped(orderId, productionId, vehicle.getStatus());
            return;
        }
        refreshStatuses(item);
    }

    /** 이미 출고된 차량은 같은 출고를 다시 받은 것이고, 취소된 차량은 정상 흐름에서 생기지 않는다. */
    private static void logNotShipped(Long orderId, Long productionId, OrderStatus status) {
        if (status == OrderStatus.CANCELLED) {
            log.warn("취소된 차량에 출고가 옴: orderId={} productionId={}", orderId, productionId);
            return;
        }
        log.info(
                "차량을 출고로 바꾸지 않음: orderId={} productionId={} vehicle={}",
                orderId,
                productionId,
                status);
    }

    /** production.cancelled: 차량을 CANCELLED 로 바꾸고 요약한다. 답이 다 왔으면 취소 요청 회차를 끝낸다. */
    @Transactional
    public void applyCancelled(Long orderId, Long productionId) {
        OrderVehicle vehicle = findVehicleOrThrow(productionId);
        if (vehicle.cancel(LocalDateTime.now())) {
            refreshStatuses(findItemById(vehicle.getOrderItemId()));
        }
        finishCancelRoundIfAnswered(orderId);
    }

    /** production.cancel-rejected: 취소 요청 중인 차량을 거절 때의 생산 상태로 되돌리고 요약한다. 답이 다 왔으면 회차를 끝낸다. */
    @Transactional
    public void applyCancelRejected(Long orderId, Long productionId, String productionStatus) {
        OrderVehicle vehicle = findVehicleOrThrow(productionId);
        if (vehicle.restoreAfterRejection(vehicleStatusOf(productionStatus))) {
            refreshStatuses(findItemById(vehicle.getOrderItemId()));
        }
        finishCancelRoundIfAnswered(orderId);
    }

    /**
     * 취소 요청 중인 차량이 더 없으면 회차를 끝낸다. 이번 회차에 취소된 차량이 있으면 order.cancelled (회차당 한 번, 늦게 온 같은 답은 회차가 이미 끝나
     * 아무것도 하지 않는다).
     */
    private void finishCancelRoundIfAnswered(Long orderId) {
        CustomerOrder order = findOrder(orderId);
        if (!order.isCancelRoundOpen()) {
            return;
        }
        List<OrderVehicle> vehicles = findVehiclesOfOrder(orderId);
        if (vehicles.stream().anyMatch(v -> v.getStatus() == OrderStatus.CANCEL_REQUESTED)) {
            return;
        }
        boolean cancelled =
                vehicles.stream().anyMatch(v -> v.isCancelledSince(order.getCancelRequestedAt()));
        order.finishCancelRound();
        if (cancelled) {
            eventPublisher.publish(new OrderCancelled(orderId));
        }
    }

    private OrderVehicle findVehicleOrThrow(Long productionId) {
        return findVehicle(productionId)
                .orElseThrow(() -> new CustomException(OrderErrorCode.ORDER_VEHICLE_NOT_FOUND));
    }

    private OrderItem findItemById(Long orderItemId) {
        return orderItemRepository
                .findById(orderItemId)
                .orElseThrow(() -> new CustomException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    private List<OrderVehicle> findVehiclesOfOrder(Long orderId) {
        return orderVehicleRepository.findAllByOrderIdOrderByProductionIdAsc(orderId);
    }

    /** 그 생산 건의 차량. 처음 보는 생산 건이면 품목에 차량을 만든다. */
    private OrderVehicle vehicleOf(OrderItem item, Long productionId) {
        return findVehicle(productionId).orElseGet(() -> addVehicle(item, productionId));
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
            case "PLANNED", "WAITING_MATERIAL" -> OrderStatus.RECEIVED;
            case "READY", "IN_PROGRESS" -> OrderStatus.IN_PRODUCTION;
            case "COMPLETED" -> OrderStatus.COMPLETED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            default -> throw new CustomException(OrderErrorCode.UNKNOWN_PRODUCTION_STATUS);
        };
    }
}

package com.factoryflow.order.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.order.domain.CustomerOrder;
import com.factoryflow.order.domain.OrderItem;
import com.factoryflow.order.domain.OrderStatus;
import com.factoryflow.order.exception.OrderErrorCode;
import com.factoryflow.order.repository.CustomerOrderRepository;
import com.factoryflow.order.repository.OrderItemRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderItemService {

    private final OrderItemRepository orderItemRepository;
    private final CustomerOrderRepository customerOrderRepository;

    /**
     * 생산 건 상태를 주문 품목에 반영하고, 바뀌었으면 같은 트랜잭션에서 주문 상태를 다시 계산한다 (docs/specs/order-production-status.md).
     */
    @Transactional
    public void applyProductionStatus(Long orderId, Long productId, String productionStatus) {
        OrderItem item = findItem(orderId, productId);
        OrderStatus next = itemStatusOf(productionStatus);
        if (!item.applyProductionStatus(next)) {
            log.info(
                    "품목 상태를 바꾸지 않음: orderId={} productId={} item={} production={}",
                    orderId,
                    productId,
                    item.getStatus(),
                    productionStatus);
            return;
        }
        findOrder(orderId)
                .refreshStatus(findItems(orderId).stream().map(OrderItem::getStatus).toList());
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

    /**
     * production.status 를 order_item.status 로 바꾼다 (docs/specs/saga-order-fulfillment.md "상태 매핑").
     */
    private static OrderStatus itemStatusOf(String productionStatus) {
        return switch (productionStatus) {
            case "PLANNED" -> OrderStatus.RECEIVED;
            case "WAITING_MATERIAL" -> OrderStatus.WAITING_MATERIAL;
            case "READY", "IN_PROGRESS" -> OrderStatus.IN_PRODUCTION;
            case "COMPLETED" -> OrderStatus.COMPLETED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            default -> throw new CustomException(OrderErrorCode.UNKNOWN_PRODUCTION_STATUS);
        };
    }
}

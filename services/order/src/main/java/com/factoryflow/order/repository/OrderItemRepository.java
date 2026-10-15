package com.factoryflow.order.repository;

import com.factoryflow.order.domain.OrderItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    Optional<OrderItem> findByOrderIdAndProductId(Long orderId, Long productId);

    List<OrderItem> findAllByOrderId(Long orderId);
}

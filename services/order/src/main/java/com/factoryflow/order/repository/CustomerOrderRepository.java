package com.factoryflow.order.repository;

import com.factoryflow.order.domain.CustomerOrder;
import com.factoryflow.order.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByOrderNo(String orderNo);

    /** beforeId 보다 오래된 주문, 최신 먼저. */
    List<CustomerOrder> findAllByIdLessThanOrderByIdDesc(Long beforeId, Limit limit);

    /** beforeId 보다 오래된 그 상태의 주문, 최신 먼저. */
    List<CustomerOrder> findAllByStatusAndIdLessThanOrderByIdDesc(
            OrderStatus status, Long beforeId, Limit limit);
}

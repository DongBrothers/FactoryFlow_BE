package com.factoryflow.order.repository;

import com.factoryflow.order.domain.CustomerOrder;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByOrderNoAndIsDeletedFalse(String orderNo);
}

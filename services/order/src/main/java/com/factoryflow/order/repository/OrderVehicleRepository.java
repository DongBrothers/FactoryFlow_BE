package com.factoryflow.order.repository;

import com.factoryflow.order.domain.OrderVehicle;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderVehicleRepository extends JpaRepository<OrderVehicle, Long> {

    Optional<OrderVehicle> findByProductionId(Long productionId);

    /** 한 품목의 차량. 품목 수량만큼만 있다. */
    List<OrderVehicle> findAllByOrderItemId(Long orderItemId);
}

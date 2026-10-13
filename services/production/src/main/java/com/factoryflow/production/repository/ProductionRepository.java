package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Production;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionRepository extends JpaRepository<Production, Long> {

    boolean existsByOrderIdAndProductId(Long orderId, Long productId);
}

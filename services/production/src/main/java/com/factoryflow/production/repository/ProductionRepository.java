package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Production;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionRepository extends JpaRepository<Production, Long> {

    List<Production> findAllByOrderIdOrderByIdAsc(Long orderId);

    List<Production> findAllByOrderIdAndProductIdIn(Long orderId, Collection<Long> productIds);
}

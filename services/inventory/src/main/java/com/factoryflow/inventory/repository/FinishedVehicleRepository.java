package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.FinishedVehicle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinishedVehicleRepository extends JpaRepository<FinishedVehicle, Long> {

    boolean existsByProductionId(Long productionId);
}

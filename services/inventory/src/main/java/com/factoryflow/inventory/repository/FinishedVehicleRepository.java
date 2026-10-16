package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.FinishedVehicle;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinishedVehicleRepository extends JpaRepository<FinishedVehicle, Long> {

    boolean existsByProductionId(Long productionId);

    Optional<FinishedVehicle> findByVinAndIsDeletedFalse(String vin);

    boolean existsByVinAndIsDeletedFalse(String vin);

    List<FinishedVehicle> findAllByVinIn(Collection<String> vins);
}

package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.FinishedVehicle;
import com.factoryflow.inventory.domain.FinishedVehicleStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinishedVehicleRepository extends JpaRepository<FinishedVehicle, Long> {

    boolean existsByProductionId(Long productionId);

    Optional<FinishedVehicle> findByVinAndIsDeletedFalse(String vin);

    boolean existsByVinAndIsDeletedFalse(String vin);

    List<FinishedVehicle> findAllByVinIn(Collection<String> vins);

    /** beforeId 보다 오래된 완성차, 최신 먼저. */
    List<FinishedVehicle> findAllByIdLessThanAndIsDeletedFalseOrderByIdDesc(
            Long beforeId, Limit limit);

    /** beforeId 보다 오래된 그 상태의 완성차, 최신 먼저. */
    List<FinishedVehicle> findAllByStatusAndIdLessThanAndIsDeletedFalseOrderByIdDesc(
            FinishedVehicleStatus status, Long beforeId, Limit limit);
}

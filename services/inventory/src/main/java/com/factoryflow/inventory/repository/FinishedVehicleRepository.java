package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.FinishedVehicle;
import com.factoryflow.inventory.domain.FinishedVehicleStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinishedVehicleRepository extends JpaRepository<FinishedVehicle, Long> {

    boolean existsByProductionId(Long productionId);

    Optional<FinishedVehicle> findByVin(String vin);

    /** 자리를 기다리는 가장 먼저 완성된 차 (창고에 있고 자리 없음). */
    Optional<FinishedVehicle> findFirstByStatusAndSlotCodeIsNullOrderByIdAsc(
            FinishedVehicleStatus status);

    long countByStatusAndSlotCodeIsNull(FinishedVehicleStatus status);

    /** beforeId 보다 오래된 완성차, 최신 먼저. */
    List<FinishedVehicle> findAllByIdLessThanOrderByIdDesc(Long beforeId, Limit limit);

    /** beforeId 보다 오래된 그 상태의 완성차, 최신 먼저. */
    List<FinishedVehicle> findAllByStatusAndIdLessThanOrderByIdDesc(
            FinishedVehicleStatus status, Long beforeId, Limit limit);
}

package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.StorageSlot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageSlotRepository extends JpaRepository<StorageSlot, Long> {

    /** 빈 자리 중 코드 순 첫 자리. */
    Optional<StorageSlot> findFirstByFinishedVehicleIdIsNullOrderByCodeAsc();

    Optional<StorageSlot> findByFinishedVehicleId(Long finishedVehicleId);

    Optional<StorageSlot> findByCode(String code);

    List<StorageSlot> findAllByOrderByCodeAsc();
}

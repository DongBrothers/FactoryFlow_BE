package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.PartLotUsage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartLotUsageRepository extends JpaRepository<PartLotUsage, Long> {

    List<PartLotUsage> findAllByVinOrderByIdAsc(String vin);

    List<PartLotUsage> findAllByLotIdOrderByIdAsc(Long lotId);
}

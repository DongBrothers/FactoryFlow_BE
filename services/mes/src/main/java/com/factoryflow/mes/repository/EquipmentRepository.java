package com.factoryflow.mes.repository;

import com.factoryflow.mes.domain.Equipment;
import com.factoryflow.mes.domain.ProductionProcess;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    Optional<Equipment> findByCodeAndIsDeletedFalse(String code);

    /** 한 공정의 설비. 공정마다 몇 대뿐이라 나누지 않는다. */
    List<Equipment> findAllByProcessAndIsDeletedFalseOrderByCodeAsc(ProductionProcess process);
}

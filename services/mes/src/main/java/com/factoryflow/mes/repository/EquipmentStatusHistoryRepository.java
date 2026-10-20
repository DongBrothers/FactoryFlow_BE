package com.factoryflow.mes.repository;

import com.factoryflow.mes.domain.EquipmentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentStatusHistoryRepository
        extends JpaRepository<EquipmentStatusHistory, Long> {}

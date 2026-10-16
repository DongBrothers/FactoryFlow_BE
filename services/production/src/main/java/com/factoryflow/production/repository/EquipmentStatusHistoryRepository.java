package com.factoryflow.production.repository;

import com.factoryflow.production.domain.EquipmentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentStatusHistoryRepository
        extends JpaRepository<EquipmentStatusHistory, Long> {}

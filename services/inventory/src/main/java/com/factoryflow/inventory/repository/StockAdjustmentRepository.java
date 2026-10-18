package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.StockAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {}

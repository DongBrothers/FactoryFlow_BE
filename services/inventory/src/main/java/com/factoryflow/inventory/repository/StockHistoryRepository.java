package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.StockHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {}

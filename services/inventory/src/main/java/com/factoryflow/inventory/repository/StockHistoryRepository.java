package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.StockHistory;
import com.factoryflow.inventory.domain.StockHistoryRefType;
import com.factoryflow.inventory.domain.StockHistoryType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    boolean existsByTypeAndRefTypeAndRefId(
            StockHistoryType type, StockHistoryRefType refType, Long refId);
}

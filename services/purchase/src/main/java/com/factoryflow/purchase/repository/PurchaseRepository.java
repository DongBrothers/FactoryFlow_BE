package com.factoryflow.purchase.repository;

import com.factoryflow.purchase.domain.Purchase;
import com.factoryflow.purchase.domain.PurchaseStatus;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    List<Purchase> findAllByStatusAndIdGreaterThanOrderByIdAsc(
            PurchaseStatus status, Long lastId, Limit limit);
}

package com.factoryflow.purchase.repository;

import com.factoryflow.purchase.domain.Purchase;
import com.factoryflow.purchase.domain.PurchaseSource;
import com.factoryflow.purchase.domain.PurchaseStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    Optional<Purchase> findFirstByPartIdAndSourceAndStatusOrderByIdAsc(
            Long partId, PurchaseSource source, PurchaseStatus status);

    List<Purchase> findAllByStatusAndIdGreaterThanOrderByIdAsc(
            PurchaseStatus status, Long lastId, Limit limit);
}

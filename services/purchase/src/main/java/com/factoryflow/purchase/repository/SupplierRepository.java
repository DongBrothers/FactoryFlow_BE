package com.factoryflow.purchase.repository;

import com.factoryflow.purchase.domain.Supplier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByIdAndIsDeletedFalse(Long id);

    List<Supplier> findAllByIsDeletedFalseOrderByIdAsc();
}

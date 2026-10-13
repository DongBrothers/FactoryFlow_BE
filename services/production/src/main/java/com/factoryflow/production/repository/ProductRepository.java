package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Product;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByIdInAndIsDeletedFalse(Collection<Long> ids);
}

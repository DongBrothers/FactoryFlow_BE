package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByIdInAndIsDeletedFalse(Collection<Long> ids);

    List<Product> findAllByIsDeletedFalseOrderByCodeAsc();

    Optional<Product> findByIdAndIsDeletedFalse(Long id);

    /** 지운 제품도 본다 (code 는 지운 제품과도 겹칠 수 없다, UNIQUE 제약). */
    boolean existsByCode(String code);
}

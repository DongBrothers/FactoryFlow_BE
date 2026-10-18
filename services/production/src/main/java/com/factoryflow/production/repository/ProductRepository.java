package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Product;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByIdInAndIsDeletedFalse(Collection<Long> ids);

    List<Product> findAllByIsDeletedFalseOrderByCodeAsc();

    Optional<Product> findByIdAndIsDeletedFalse(Long id);

    /**
     * 수정할 제품을 잠그고 읽는다 (SELECT … FOR UPDATE). 같은 제품을 동시에 고치면 한 번에 하나씩 처리해, 둘 다 같은 새 부품을 넣어도 BOM 줄을 두
     * 번 만들지 않는다 (docs/specs/master-data.md).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Product> findForUpdateByIdAndIsDeletedFalse(Long id);

    /** 지운 제품도 본다 (code 는 지운 제품과도 겹칠 수 없다, UNIQUE 제약). */
    boolean existsByCode(String code);
}

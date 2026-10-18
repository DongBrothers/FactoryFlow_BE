package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Bom;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BomRepository extends JpaRepository<Bom, Long>, BomRepositoryCustom {

    List<Bom> findAllByProductIdInAndIsDeletedFalse(Collection<Long> productIds);

    List<Bom> findAllByProductIdAndIsDeletedFalseOrderByPartIdAsc(Long productId);

    /** 지운 줄도 본다 (BOM 수정 때 지웠던 부품을 되살린다). */
    List<Bom> findAllByProductId(Long productId);
}

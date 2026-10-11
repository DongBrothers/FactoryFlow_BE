package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Bom;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BomRepository extends JpaRepository<Bom, Long> {

    /** 주어진 제품 중 BOM 이 하나 이상 있는 제품 id. */
    @Query(
            "SELECT DISTINCT b.productId FROM Bom b"
                    + " WHERE b.productId IN :productIds AND b.isDeleted = false")
    List<Long> findProductIdsHavingBom(@Param("productIds") Collection<Long> productIds);
}

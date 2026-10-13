package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Bom;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BomRepository extends JpaRepository<Bom, Long>, BomRepositoryCustom {

    List<Bom> findAllByProductIdInAndIsDeletedFalse(Collection<Long> productIds);
}

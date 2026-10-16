package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.PartLot;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartLotRepository extends JpaRepository<PartLot, Long> {

    /** 그 부품들의 아직 쓰지 않은 LOT, 입고 순서(id). */
    List<PartLot> findAllByPartIdInAndRemainingQtyGreaterThanOrderByIdAsc(
            Collection<Long> partIds, int remainingQty);

    List<PartLot> findAllByIdIn(Collection<Long> ids);

    Optional<PartLot> findByLotNoAndIsDeletedFalse(String lotNo);
}

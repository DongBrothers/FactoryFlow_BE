package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.PartLot;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartLotRepository extends JpaRepository<PartLot, Long> {

    /** 그 부품들의 아직 쓰지 않은 LOT, 입고 순서(입고 시각, 같으면 id). 초기 재고 LOT 이 가장 먼저다 (V6). */
    List<PartLot> findAllByPartIdInAndRemainingQtyGreaterThanOrderByReceivedAtAscIdAsc(
            Collection<Long> partIds, int remainingQty);

    List<PartLot> findAllByIdIn(Collection<Long> ids);

    Optional<PartLot> findByLotNo(String lotNo);
}

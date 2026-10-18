package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Production;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ProductionRepository
        extends JpaRepository<Production, Long>, ProductionRepositoryCustom {

    List<Production> findAllByOrderIdOrderByIdAsc(Long orderId);

    /**
     * 작업지시를 발행할 생산 건을 잠그고 version 을 바로 올린다 (SELECT … FOR UPDATE + UPDATE version). 같은 차의 취소 수신이 먼저
     * 읽은 상태로 저장하려 하면 충돌해 재시도하고, 재시도 때 발행된 작업지시를 보고 함께 취소한다 (docs/specs/work-order-release.md).
     */
    @Lock(LockModeType.PESSIMISTIC_FORCE_INCREMENT)
    Optional<Production> findForReleaseById(Long id);

    List<Production> findAllByOrderIdAndProductIdIn(Long orderId, Collection<Long> productIds);
}

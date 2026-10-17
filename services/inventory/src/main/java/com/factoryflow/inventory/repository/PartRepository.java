package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.Part;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PartRepository extends JpaRepository<Part, Long> {

    /**
     * 예약할 부품을 읽는다. 값을 바꾸지 않아도(WAITING) 커밋 때 version 을 올려, 같은 부품을 동시에 처리한 다른 요청이 커밋에서 충돌하고 재시도하게 한다
     * (서로의 커밋 전 WAITING 줄을 못 보고 새치기하는 것을 막는다).
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    List<Part> findAllByIdInAndIsDeletedFalse(Collection<Long> ids);

    List<Part> findAllByIsDeletedFalseOrderByCodeAsc();

    /** 조회용 (version 을 올리지 않는다). 삭제된 부품은 빠진다. */
    List<Part> findAllByIdInAndIsDeletedFalseOrderByCodeAsc(Collection<Long> ids);

    Optional<Part> findByIdAndIsDeletedFalse(Long id);
}

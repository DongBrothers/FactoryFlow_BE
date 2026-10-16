package com.factoryflow.production.repository;

import com.factoryflow.production.domain.WorkOrder;
import com.factoryflow.production.domain.WorkOrderStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    boolean existsByProductionId(Long productionId);

    List<WorkOrder> findAllByProductionIdIn(Collection<Long> productionIds);

    /** id 커서 페이지: lastId 다음부터 limit 개 (CursorPage). */
    List<WorkOrder> findAllByStatusAndIdGreaterThanOrderByIdAsc(
            WorkOrderStatus status, Long lastId, Limit limit);
}

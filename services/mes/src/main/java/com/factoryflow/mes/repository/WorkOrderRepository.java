package com.factoryflow.mes.repository;

import com.factoryflow.mes.domain.WorkOrder;
import com.factoryflow.mes.domain.WorkOrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    boolean existsByProductionId(Long productionId);

    Optional<WorkOrder> findByProductionId(Long productionId);

    Optional<WorkOrder> findByVin(String vin);

    /** id 커서 페이지: lastId 다음부터 limit 개 (CursorPage). */
    List<WorkOrder> findAllByStatusAndIdGreaterThanOrderByIdAsc(
            WorkOrderStatus status, Long lastId, Limit limit);
}

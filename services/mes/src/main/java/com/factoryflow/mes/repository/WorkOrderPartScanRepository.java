package com.factoryflow.mes.repository;

import com.factoryflow.mes.domain.WorkOrderPartScan;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderPartScanRepository extends JpaRepository<WorkOrderPartScan, Long> {

    List<WorkOrderPartScan> findAllByWorkOrderIdOrderByPartIdAsc(Long workOrderId);

    void deleteAllByWorkOrderId(Long workOrderId);
}

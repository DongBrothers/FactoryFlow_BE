package com.factoryflow.production.repository;

import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.domain.StepStatus;
import com.factoryflow.production.domain.WorkOrderStep;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderStepRepository
        extends JpaRepository<WorkOrderStep, Long>, WorkOrderStepRepositoryCustom {

    /** 한 작업지시의 공정 5개, 라우팅 순서. */
    List<WorkOrderStep> findAllByWorkOrderIdOrderBySeqAsc(Long workOrderId);

    /** 목록 한 페이지의 작업지시들의 공정 (페이지 크기 × 5). */
    List<WorkOrderStep> findAllByWorkOrderIdInOrderBySeqAsc(Collection<Long> workOrderIds);

    /** 한 공정에서 설비를 쓰고 있는(배정·진행 중) 공정. 설비 한 대에 하나뿐이라 설비 수를 넘지 않는다. */
    List<WorkOrderStep> findAllByProcessAndStatusIn(
            ProductionProcess process, Collection<StepStatus> statuses);

    boolean existsByEquipmentCodeAndStatusIn(String equipmentCode, Collection<StepStatus> statuses);
}

package com.factoryflow.mes.repository;

import com.factoryflow.mes.domain.StepStatus;
import com.factoryflow.mes.domain.WorkOrderStep;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderStepRepository
        extends JpaRepository<WorkOrderStep, Long>, WorkOrderStepRepositoryCustom {

    /** 한 작업지시의 공정 5개, 라우팅 순서. */
    List<WorkOrderStep> findAllByWorkOrderIdOrderBySeqAsc(Long workOrderId);

    /** 목록 한 페이지의 작업지시들의 공정 (페이지 크기 × 5). */
    List<WorkOrderStep> findAllByWorkOrderIdInOrderBySeqAsc(Collection<Long> workOrderIds);

    /** 시작 보고 없이 deadline 전에 배정된 공정, 오래된 순서로. */
    List<WorkOrderStep> findAllByStatusAndDispatchedAtBeforeOrderByDispatchedAtAsc(
            StepStatus status, LocalDateTime deadline, Limit limit);

    /** 배정으로 시작해(설비 진행) deadline 전에 시작한 공정, 오래된 순서로. 작업자가 시작한 공정은 dispatchedAt 이 없어 빠진다. */
    List<WorkOrderStep>
            findAllByStatusAndDispatchedAtIsNotNullAndStartedAtBeforeOrderByStartedAtAsc(
                    StepStatus status, LocalDateTime deadline, Limit limit);
}

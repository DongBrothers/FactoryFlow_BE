package com.factoryflow.mes.repository;

import static com.factoryflow.mes.domain.QWorkOrder.workOrder;
import static com.factoryflow.mes.domain.QWorkOrderStep.workOrderStep;

import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.domain.QWorkOrderStep;
import com.factoryflow.mes.domain.StepStatus;
import com.factoryflow.mes.domain.WorkOrderStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WorkOrderStepRepositoryImpl implements WorkOrderStepRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Long> findDispatchableWorkOrderIds(
            ProductionProcess process, Collection<Long> excludedIds, int limit) {
        BooleanBuilder where =
                new BooleanBuilder()
                        .and(workOrderStep.process.eq(process))
                        .and(workOrderStep.status.eq(StepStatus.PENDING))
                        .and(
                                workOrder.status.in(
                                        WorkOrderStatus.RELEASED, WorkOrderStatus.IN_PROGRESS));
        if (process.seq() > 1) {
            where.and(previousStepDone());
        }
        if (!excludedIds.isEmpty()) {
            where.and(workOrderStep.workOrderId.notIn(excludedIds));
        }
        return queryFactory
                .select(workOrderStep.workOrderId)
                .from(workOrderStep)
                .join(workOrder)
                .on(workOrder.id.eq(workOrderStep.workOrderId))
                .where(where)
                .orderBy(workOrderStep.workOrderId.asc())
                .limit(limit)
                .fetch();
    }

    /** 바로 앞 공정이 DONE 이다. 앞 공정은 그 앞이 모두 끝나야 시작되므로 바로 앞만 보면 된다. */
    private static Predicate previousStepDone() {
        QWorkOrderStep previous = new QWorkOrderStep("previous");
        return JPAExpressions.selectOne()
                .from(previous)
                .where(
                        previous.workOrderId.eq(workOrderStep.workOrderId),
                        previous.seq.eq(workOrderStep.seq.subtract(1)),
                        previous.status.eq(StepStatus.DONE))
                .exists();
    }
}

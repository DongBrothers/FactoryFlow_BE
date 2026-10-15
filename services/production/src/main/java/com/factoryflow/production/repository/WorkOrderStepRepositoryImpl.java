package com.factoryflow.production.repository;

import static com.factoryflow.production.domain.QWorkOrder.workOrder;
import static com.factoryflow.production.domain.QWorkOrderStep.workOrderStep;

import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.domain.QWorkOrderStep;
import com.factoryflow.production.domain.StepStatus;
import com.factoryflow.production.domain.WorkOrderStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WorkOrderStepRepositoryImpl implements WorkOrderStepRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Long> findDispatchableWorkOrderIds(
            ProductionProcess process,
            LocalDateTime releasedBefore,
            Collection<Long> excludedIds,
            int limit) {
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
        if (releasedBefore != null) {
            where.and(workOrder.releasedAt.loe(releasedBefore));
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

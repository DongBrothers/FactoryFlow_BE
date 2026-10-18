package com.factoryflow.production.repository;

import static com.factoryflow.production.domain.QProduction.production;
import static com.factoryflow.production.domain.QWorkOrder.workOrder;

import com.factoryflow.production.domain.Production;
import com.factoryflow.production.domain.ProductionStatus;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProductionRepositoryImpl implements ProductionRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Production> findAwaitingRelease(Long lastId, int limit) {
        return queryFactory
                .selectFrom(production)
                .where(
                        production.status.eq(ProductionStatus.READY),
                        production.id.gt(lastId),
                        JPAExpressions.selectOne()
                                .from(workOrder)
                                .where(workOrder.productionId.eq(production.id))
                                .notExists())
                .orderBy(production.id.asc())
                .limit(limit)
                .fetch();
    }
}

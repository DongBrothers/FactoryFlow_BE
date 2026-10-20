package com.factoryflow.production.repository;

import static com.factoryflow.production.domain.QProduction.production;

import com.factoryflow.production.domain.Production;
import com.factoryflow.production.domain.ProductionStatus;
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
                        production.releasedAt.isNull(),
                        production.id.gt(lastId))
                .orderBy(production.id.asc())
                .limit(limit)
                .fetch();
    }
}

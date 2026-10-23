package com.factoryflow.purchase.repository;

import static com.factoryflow.purchase.domain.QPurchase.purchase;

import com.factoryflow.purchase.domain.PurchaseStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PurchaseRepositoryImpl implements PurchaseRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<OrderedTotal> sumOrderedQtyByPart(Collection<Long> partIds) {
        return queryFactory
                .select(
                        Projections.constructor(
                                OrderedTotal.class, purchase.partId, purchase.qty.sumLong()))
                .from(purchase)
                .where(purchase.partId.in(partIds), purchase.status.eq(PurchaseStatus.ORDERED))
                .groupBy(purchase.partId)
                .fetch();
    }

    @Override
    public List<Long> findLatestIdsByPart(Collection<Long> partIds) {
        return queryFactory
                .select(purchase.id.max())
                .from(purchase)
                .where(purchase.partId.in(partIds))
                .groupBy(purchase.partId)
                .fetch();
    }
}

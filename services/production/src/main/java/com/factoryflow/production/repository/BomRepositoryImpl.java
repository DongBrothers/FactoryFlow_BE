package com.factoryflow.production.repository;

import static com.factoryflow.production.domain.QBom.bom;

import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BomRepositoryImpl implements BomRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Long> findProductIdsHavingBom(Collection<Long> productIds) {
        return queryFactory
                .select(bom.productId)
                .distinct()
                .from(bom)
                .where(bom.productId.in(productIds), bom.isDeleted.isFalse())
                .fetch();
    }
}

package com.factoryflow.inventory.repository;

import static com.factoryflow.inventory.domain.QPartReservation.partReservation;

import com.factoryflow.inventory.domain.ReservationStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PartReservationRepositoryImpl implements PartReservationRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<WaitingTotal> sumWaitingByPart() {
        return queryFactory
                .select(
                        Projections.constructor(
                                WaitingTotal.class,
                                partReservation.partId,
                                partReservation.qty.sumLong(),
                                partReservation.productionId.countDistinct()))
                .from(partReservation)
                .where(partReservation.status.eq(ReservationStatus.WAITING))
                .groupBy(partReservation.partId)
                .fetch();
    }
}

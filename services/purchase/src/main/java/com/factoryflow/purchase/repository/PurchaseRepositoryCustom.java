package com.factoryflow.purchase.repository;

import java.util.Collection;
import java.util.List;

public interface PurchaseRepositoryCustom {

    /** 부품마다 발주 중(ORDERED) 구매 건 qty 합. ORDERED 가 없는 부품은 빠진다. */
    List<OrderedTotal> sumOrderedQtyByPart(Collection<Long> partIds);

    /** 부품마다 가장 최근에 만든 구매 건(id 가 가장 큰 것, 상태 무관)의 id. 산 적 없는 부품은 빠진다. */
    List<Long> findLatestIdsByPart(Collection<Long> partIds);
}

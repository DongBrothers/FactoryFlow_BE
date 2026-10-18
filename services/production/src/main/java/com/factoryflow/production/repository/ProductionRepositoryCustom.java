package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Production;
import java.util.List;

public interface ProductionRepositoryCustom {

    /** 작업지시 발행 대기: READY 이고 작업지시가 없는 생산 건, lastId 다음부터 id 순으로 limit 개. */
    List<Production> findAwaitingRelease(Long lastId, int limit);
}

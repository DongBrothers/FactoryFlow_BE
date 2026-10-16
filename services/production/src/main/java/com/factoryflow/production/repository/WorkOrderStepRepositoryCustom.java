package com.factoryflow.production.repository;

import com.factoryflow.production.domain.ProductionProcess;
import java.util.Collection;
import java.util.List;

public interface WorkOrderStepRepositoryCustom {

    /**
     * 이 공정을 지금 배정할 수 있는 작업지시 id, 먼저 발행된 순서로 limit 개: 이 공정이 PENDING 이고, 앞 공정이 DONE 이며(첫 공정이면 조건 없음),
     * 작업지시가 RELEASED 또는 IN_PROGRESS. excludedIds 는 빼고 고른다 (이번 바퀴에 배정에 실패한 작업지시).
     */
    List<Long> findDispatchableWorkOrderIds(
            ProductionProcess process, Collection<Long> excludedIds, int limit);
}

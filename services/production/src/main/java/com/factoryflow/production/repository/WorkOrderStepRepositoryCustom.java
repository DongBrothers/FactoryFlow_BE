package com.factoryflow.production.repository;

import com.factoryflow.production.domain.ProductionProcess;
import java.time.LocalDateTime;
import java.util.List;

public interface WorkOrderStepRepositoryCustom {

    /**
     * 이 공정을 지금 배정할 수 있는 작업지시 id, 먼저 발행된 순서로 limit 개: 이 공정이 PENDING 이고, 앞 공정이 DONE 이며(첫 공정이면 조건 없음),
     * 작업지시가 RELEASED 또는 IN_PROGRESS. releasedBefore 가 있으면 그 전에 발행된 작업지시만 (프레스 대기 시간).
     */
    List<Long> findDispatchableWorkOrderIds(
            ProductionProcess process, LocalDateTime releasedBefore, int limit);
}

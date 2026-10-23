package com.factoryflow.mes.repository;

import com.factoryflow.mes.domain.EquipmentStatusHistory;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentStatusHistoryRepository
        extends JpaRepository<EquipmentStatusHistory, Long> {

    /** 최신 먼저 id 커서 페이지: beforeId 보다 앞(작은 id)부터 limit 개 (CursorPage). */
    List<EquipmentStatusHistory> findAllByEquipmentCodeAndIdLessThanOrderByIdDesc(
            String equipmentCode, Long beforeId, Limit limit);
}

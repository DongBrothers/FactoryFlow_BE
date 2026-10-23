package com.factoryflow.mes.repository;

import com.factoryflow.mes.domain.ProductionResult;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionResultRepository extends JpaRepository<ProductionResult, Long> {

    /** 한 작업지시의 공정들의 실적, 입력한 순서. 공정마다 한 줄이다 (불량이면 폐기). */
    List<ProductionResult> findAllByWorkOrderStepIdInOrderByIdAsc(Collection<Long> stepIds);
}

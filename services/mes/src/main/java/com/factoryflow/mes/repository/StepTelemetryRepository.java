package com.factoryflow.mes.repository;

import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.domain.StepTelemetry;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Range;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StepTelemetryRepository extends MongoRepository<StepTelemetry, String> {

    /** 작업지시 한 공정의 센서값, 회차·시간 순. */
    List<StepTelemetry>
            findAllByMetaWorkOrderIdAndMetaProcessOrderByMetaDispatchSeqAscMeasuredAtAsc(
                    Long workOrderId, ProductionProcess process, Limit limit);

    /** 설비 하나의 범위 안 센서값, 시간 순. 같은 필드에 조건 두 개를 따로 걸 수 없어 Range 하나로 넘긴다. */
    List<StepTelemetry> findAllByMetaEquipmentCodeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            String equipmentCode, Range<Instant> measuredAt, Limit limit);
}

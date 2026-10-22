package com.factoryflow.mes.domain;

import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.TimeSeries;
import org.springframework.data.mongodb.core.timeseries.Granularity;

/**
 * 로봇 공정 중 설비가 남긴 센서값 한 개 (MongoDB 시계열 컬렉션 step_telemetry, docs/specs/process-telemetry.md). 추가만 하고
 * 고치지 않으며, 90일 뒤 MongoDB 가 지운다.
 */
@Getter
@Document("step_telemetry")
@TimeSeries(
        timeField = "measuredAt",
        metaField = "meta",
        granularity = Granularity.SECONDS,
        expireAfter = "90d")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StepTelemetry {

    @Id private String id;

    private Instant measuredAt;

    private TelemetrySource meta;

    /** 항목 이름(TelemetryParameter.key)과 값. */
    private Map<String, Double> values;

    private StepTelemetry(Instant measuredAt, TelemetrySource meta, Map<String, Double> values) {
        this.measuredAt = measuredAt;
        this.meta = meta;
        this.values = values;
    }

    public static StepTelemetry record(
            Instant measuredAt, TelemetrySource meta, Map<String, Double> values) {
        return new StepTelemetry(measuredAt, meta, values);
    }
}

package com.factoryflow.simulator.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 로봇 공정 하나를 하는 동안의 센서값을 만든다 (docs/specs/process-telemetry.md). 값은 정상 범위 안에서 흔들리고, 불량으로 보고하는 공정은 대표
 * 항목(defectSignal)이 공정 중간부터 범위를 벗어난다. 메모리를 지키려고 공정당 MAX_READINGS 개까지만 만든다.
 */
public final class TelemetryRecorder {

    /** 공정 하나의 센서값 상한 (OOM 방지). */
    public static final int MAX_READINGS = 300;

    /** 수집 간격 하한. 너무 짧으면 개수가 상한에 바로 닿는다. */
    public static final Duration MIN_INTERVAL = Duration.ofMillis(50);

    /**
     * 정상값은 범위의 10% ~ 90% 사이 (경계에 붙지 않게 양쪽 10% 를 남긴다). NORMAL_MARGIN * 2 + NORMAL_SPREAD 는 1 을 넘지
     * 않는다.
     */
    private static final double NORMAL_MARGIN = 0.1;

    private static final double NORMAL_SPREAD = 0.8;

    /** 불량 대표 항목은 상한보다 범위 폭의 10% ~ 30% 위 (분명히 벗어나게). */
    private static final double DEFECT_MARGIN = 0.1;

    private static final double DEFECT_SPREAD = 0.2;

    private TelemetryRecorder() {}

    /** start 부터 work 동안 interval 마다 잰 값. 사람 공정이면 빈 목록. */
    public static List<TelemetryReading> record(
            LineProcess process,
            Instant start,
            Duration work,
            Duration interval,
            boolean defect,
            RandomGenerator random) {
        List<TelemetryParameter> parameters = TelemetryParameter.of(process);
        if (parameters.isEmpty()) {
            return List.of();
        }
        Duration step = interval.compareTo(MIN_INTERVAL) < 0 ? MIN_INTERVAL : interval;
        int count = (int) Math.min(MAX_READINGS, Math.max(1, work.toMillis() / step.toMillis()));
        List<TelemetryReading> readings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean outOfRange = defect && i >= count / 2;
            readings.add(
                    new TelemetryReading(
                            start.plus(step.multipliedBy(i)),
                            valuesOf(parameters, outOfRange, random)));
        }
        return readings;
    }

    private static Map<String, Double> valuesOf(
            List<TelemetryParameter> parameters, boolean outOfRange, RandomGenerator random) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (TelemetryParameter p : parameters) {
            double span = p.upper() - p.lower();
            double value =
                    outOfRange && p.defectSignal()
                            ? p.upper()
                                    + span * (DEFECT_MARGIN + DEFECT_SPREAD * random.nextDouble())
                            : p.lower()
                                    + span * (NORMAL_MARGIN + NORMAL_SPREAD * random.nextDouble());
            values.put(p.key(), Math.round(value * 100) / 100.0);
        }
        return values;
    }
}

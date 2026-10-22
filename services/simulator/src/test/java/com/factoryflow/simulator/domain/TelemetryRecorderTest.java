package com.factoryflow.simulator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 로봇 공정 센서값 만들기 (docs/specs/process-telemetry.md "1. simulator: 값 만들기", "메모리 상한"). */
class TelemetryRecorderTest {

    static final Instant START = Instant.parse("2026-10-21T01:00:00Z");

    @Test
    void readingsCoverTheWorkTimeAtTheIntervalWithTheProcessItems() {
        List<TelemetryReading> readings =
                TelemetryRecorder.record(
                        LineProcess.PRESS,
                        START,
                        Duration.ofSeconds(3),
                        Duration.ofMillis(200),
                        false,
                        new Random(1));

        assertThat(readings).hasSize(15);
        assertThat(readings.get(0).at()).isEqualTo(START);
        assertThat(readings.get(1).at()).isEqualTo(START.plusMillis(200));
        assertThat(readings.get(0).values()).containsOnlyKeys("pressureTon", "strokeMm");
    }

    @Test
    void goodStepsStayInsideEveryRange() {
        for (LineProcess process :
                List.of(LineProcess.PRESS, LineProcess.BODY, LineProcess.PAINT)) {
            List<TelemetryReading> readings =
                    TelemetryRecorder.record(
                            process,
                            START,
                            Duration.ofSeconds(5),
                            Duration.ofMillis(100),
                            false,
                            new Random(2));

            for (TelemetryParameter p : TelemetryParameter.of(process)) {
                assertThat(readings)
                        .extracting(r -> r.values().get(p.key()))
                        .allSatisfy(v -> assertThat(v).isBetween(p.lower(), p.upper()));
            }
        }
    }

    @Test
    void defectStepsPushTheSignalItemOutOfRangeFromTheMiddle() {
        List<TelemetryReading> readings =
                TelemetryRecorder.record(
                        LineProcess.PAINT,
                        START,
                        Duration.ofSeconds(2),
                        Duration.ofMillis(100),
                        true,
                        new Random(3));

        assertThat(readings).hasSize(20);
        assertThat(readings.subList(0, 10))
                .extracting(r -> r.values().get("filmThicknessUm"))
                .allSatisfy(v -> assertThat(v).isBetween(100.0, 120.0));
        assertThat(readings.subList(10, 20))
                .extracting(r -> r.values().get("filmThicknessUm"))
                .allSatisfy(v -> assertThat(v).isGreaterThan(120.0));
        // 대표 항목이 아닌 값은 범위 안에 있다
        assertThat(readings)
                .extracting(r -> r.values().get("boothTempC"))
                .allSatisfy(v -> assertThat(v).isBetween(21.0, 25.0));
    }

    @Test
    void readingsAreCappedAndTheIntervalHasAFloor() {
        // 1시간 작업을 1ms 간격으로 재도 간격은 50ms, 개수는 300 을 넘지 않는다 (OOM 방지)
        List<TelemetryReading> readings =
                TelemetryRecorder.record(
                        LineProcess.BODY,
                        START,
                        Duration.ofHours(1),
                        Duration.ofMillis(1),
                        false,
                        new Random(4));

        assertThat(readings).hasSize(TelemetryRecorder.MAX_READINGS);
        assertThat(Duration.between(readings.get(0).at(), readings.get(1).at()))
                .isEqualTo(TelemetryRecorder.MIN_INTERVAL);
    }

    @Test
    void humanStepsHaveNoReadings() {
        assertThat(
                        TelemetryRecorder.record(
                                LineProcess.ASSEMBLY,
                                START,
                                Duration.ofSeconds(3),
                                Duration.ofMillis(200),
                                false,
                                new Random(5)))
                .isEmpty();
    }
}

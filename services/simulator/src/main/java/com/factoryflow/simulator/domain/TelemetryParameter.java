package com.factoryflow.simulator.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 로봇 공정 설비가 재는 항목과 정상 범위 (docs/specs/process-telemetry.md). mes 의 TelemetryParameter 와 같은 표다 (서비스끼리
 * 코드를 공유하지 않는다). defectSignal 인 항목은 불량으로 보고하는 공정에서 범위를 벗어난다.
 */
public enum TelemetryParameter {
    PRESSURE_TON(LineProcess.PRESS, "pressureTon", 780, 820, true),
    STROKE_MM(LineProcess.PRESS, "strokeMm", 295, 305, false),
    WELD_CURRENT_KA(LineProcess.BODY, "weldCurrentKa", 9.5, 10.5, true),
    WELD_VOLTAGE_V(LineProcess.BODY, "weldVoltageV", 22, 26, false),
    BOOTH_TEMP_C(LineProcess.PAINT, "boothTempC", 21, 25, false),
    HUMIDITY_PCT(LineProcess.PAINT, "humidityPct", 55, 65, false),
    FILM_THICKNESS_UM(LineProcess.PAINT, "filmThicknessUm", 100, 120, true);

    private final LineProcess process;
    private final String key;
    private final double lower;
    private final double upper;
    private final boolean defectSignal;

    TelemetryParameter(
            LineProcess process, String key, double lower, double upper, boolean defectSignal) {
        this.process = process;
        this.key = key;
        this.lower = lower;
        this.upper = upper;
        this.defectSignal = defectSignal;
    }

    /** 그 공정의 항목. 사람 공정이면 빈 목록. */
    public static List<TelemetryParameter> of(LineProcess process) {
        return Arrays.stream(values()).filter(p -> p.process == process).toList();
    }

    public String key() {
        return key;
    }

    public double lower() {
        return lower;
    }

    public double upper() {
        return upper;
    }

    public boolean defectSignal() {
        return defectSignal;
    }
}

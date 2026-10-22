package com.factoryflow.mes.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 로봇 공정 설비가 보내는 센서 항목과 정상 범위 (docs/specs/process-telemetry.md). 화면의 기준선으로 쓴다. simulator 도 같은 표를 갖는다
 * (서비스끼리 코드를 공유하지 않는다).
 */
public enum TelemetryParameter {
    PRESSURE_TON(ProductionProcess.PRESS, "pressureTon", "ton", 780, 820),
    STROKE_MM(ProductionProcess.PRESS, "strokeMm", "mm", 295, 305),
    WELD_CURRENT_KA(ProductionProcess.BODY, "weldCurrentKa", "kA", 9.5, 10.5),
    WELD_VOLTAGE_V(ProductionProcess.BODY, "weldVoltageV", "V", 22, 26),
    BOOTH_TEMP_C(ProductionProcess.PAINT, "boothTempC", "°C", 21, 25),
    HUMIDITY_PCT(ProductionProcess.PAINT, "humidityPct", "%", 55, 65),
    FILM_THICKNESS_UM(ProductionProcess.PAINT, "filmThicknessUm", "µm", 100, 120);

    private final ProductionProcess process;
    private final String key;
    private final String unit;
    private final double lower;
    private final double upper;

    TelemetryParameter(
            ProductionProcess process, String key, String unit, double lower, double upper) {
        this.process = process;
        this.key = key;
        this.unit = unit;
        this.lower = lower;
        this.upper = upper;
    }

    /** 그 공정의 항목, 표 순서. 사람 공정이면 빈 목록. */
    public static List<TelemetryParameter> of(ProductionProcess process) {
        return Arrays.stream(values()).filter(p -> p.process == process).toList();
    }

    public String key() {
        return key;
    }

    public String unit() {
        return unit;
    }

    public double lower() {
        return lower;
    }

    public double upper() {
        return upper;
    }
}

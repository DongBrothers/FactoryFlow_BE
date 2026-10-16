package com.factoryflow.simulator.domain;

/** 공정. production 의 ProductionProcess 와 같은 이름이다. 로봇 공정만 배정을 받는다. */
public enum LineProcess {
    PRESS(true, "찍힘"),
    BODY(true, "용접 불량"),
    PAINT(true, "도장 얼룩"),
    ASSEMBLY(false, "조립 불량"),
    INSPECTION(false, "검사 불합격");

    private final boolean automated;
    private final String defectReason;

    LineProcess(boolean automated, String defectReason) {
        this.automated = automated;
        this.defectReason = defectReason;
    }

    /** 로봇 공정인가. 사람 공정(의장·검사) 설비는 배정을 받지 않는다 (작업자 API 로 진행). */
    public boolean isAutomated() {
        return automated;
    }

    /** 불량이 나면 보고하는 사유. */
    public String defectReason() {
        return defectReason;
    }
}

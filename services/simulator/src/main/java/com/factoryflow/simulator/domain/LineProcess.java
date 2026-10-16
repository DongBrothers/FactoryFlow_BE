package com.factoryflow.simulator.domain;

/** 공정. production 의 ProductionProcess 와 같은 이름이다. 로봇 공정만 배정을 받는다. */
public enum LineProcess {
    PRESS("찍힘"),
    BODY("용접 불량"),
    PAINT("도장 얼룩"),
    ASSEMBLY("조립 불량"),
    INSPECTION("검사 불합격");

    private final String defectReason;

    LineProcess(String defectReason) {
        this.defectReason = defectReason;
    }

    /** 불량이 나면 보고하는 사유. */
    public String defectReason() {
        return defectReason;
    }
}

package com.factoryflow.mes.domain;

/**
 * 공정. 선언 순서가 라우팅 순서다 (모든 차종 공통, docs/planning/production-flow.md). 로봇 공정은 production 이 설비에 배정하고 설비가
 * 보고하며, 사람 공정은 작업자가 진행한다 (혼합 자동화).
 */
public enum ProductionProcess {
    /** 프레스 (첫 공정, 로봇. 배정되면 취소할 수 없다) */
    PRESS(true),
    /** 차체 (용접, 로봇) */
    BODY(true),
    /** 도장 (로봇) */
    PAINT(true),
    /** 의장 (조립, 사람) */
    ASSEMBLY(false),
    /** 검사 (마지막 공정, 사람) */
    INSPECTION(false);

    private final boolean automated;

    ProductionProcess(boolean automated) {
        this.automated = automated;
    }

    /** 로봇 공정인가 (배정 스케줄러가 설비에 배정한다). */
    public boolean isAutomated() {
        return automated;
    }

    /** 마지막 공정(검사)인가. 양품으로 끝나면 차가 완성된다. */
    public boolean isLast() {
        return this == INSPECTION;
    }

    /** 라우팅 순서 1~5. */
    public int seq() {
        return ordinal() + 1;
    }
}

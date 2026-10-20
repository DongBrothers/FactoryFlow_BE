package com.factoryflow.mes.domain;

/** 공정 실적 결과. */
public enum StepResult {
    /** 양품. 공정이 끝나고 다음 공정으로 */
    GOOD,
    /** 불량. 실적만 남기고 같은 공정을 다시 한다 */
    DEFECT
}

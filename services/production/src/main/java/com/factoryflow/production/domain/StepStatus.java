package com.factoryflow.production.domain;

/** 공정 상태. */
public enum StepStatus {
    /** 시작 전, 또는 불량으로 재작업을 기다림 */
    PENDING,
    /** 로봇 공정이 설비에 배정됨. 설비의 시작 보고를 기다린다 */
    DISPATCHED,
    /** 진행 중 */
    IN_PROGRESS,
    /** 양품으로 끝남 */
    DONE
}

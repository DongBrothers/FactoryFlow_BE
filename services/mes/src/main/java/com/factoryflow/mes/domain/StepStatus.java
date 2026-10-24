package com.factoryflow.mes.domain;

/** 공정 상태. */
public enum StepStatus {
    /** 시작 전 (설비 고장·시간 초과로 되돌린 공정 포함) */
    PENDING,
    /** 로봇 공정이 설비에 배정됨. 설비의 시작 보고를 기다린다 */
    DISPATCHED,
    /** 진행 중 */
    IN_PROGRESS,
    /** 양품으로 끝남 */
    DONE
}

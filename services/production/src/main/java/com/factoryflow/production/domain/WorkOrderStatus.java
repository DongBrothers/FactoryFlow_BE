package com.factoryflow.production.domain;

/** 작업지시 상태. */
public enum WorkOrderStatus {
    /** 발행됨. 첫 공정을 기다린다 */
    RELEASED,
    /** 첫 공정(프레스)을 시작함 */
    IN_PROGRESS,
    /** 검사 완료, VIN 발행 */
    COMPLETED,
    /** 첫 공정 시작 전에 취소됨 */
    CANCELLED
}

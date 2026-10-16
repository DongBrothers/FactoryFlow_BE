package com.factoryflow.production.domain;

/** 설비 상태. simulator 의 equipment.status-changed 로 바뀐다. */
public enum EquipmentStatus {
    /** 가동. 이 상태에서만 공정을 시작할 수 있다 */
    RUNNING,
    /** 정지. 이 설비가 맡은 공정을 되돌려 다른 설비로 재배정한다 */
    STOPPED,
    /** 고장. 정지처럼 이 설비가 맡은 공정을 되돌려 다른 설비로 재배정한다 (생산 실패 없음) */
    FAULT
}

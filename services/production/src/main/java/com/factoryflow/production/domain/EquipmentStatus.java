package com.factoryflow.production.domain;

/** 설비 상태. simulator 의 equipment.status-changed 로 바뀐다. */
public enum EquipmentStatus {
    /** 가동. 이 상태에서만 공정을 시작할 수 있다 */
    RUNNING,
    /** 정지 */
    STOPPED,
    /** 고장. 이 설비에서 진행 중인 차량은 생산 실패 */
    FAULT
}

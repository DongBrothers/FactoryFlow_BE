package com.factoryflow.production.scheduler;

/** 배정 시도 한 번의 결과. 실패를 차 쪽과 설비 쪽으로 나눠, 설비 문제로 차를 빼지 않게 한다. */
public enum DispatchOutcome {
    /** 배정됨. 그 설비를 쓴다 */
    DISPATCHED,
    /** 설비 쪽 이유 (그사이 정지, 다른 차가 먼저 잡음). 그 설비만 빼고 같은 차로 다음 설비를 시도한다 */
    EQUIPMENT_FAILED,
    /** 차 쪽 이유 (상태가 바뀜, 생산 건을 시작할 수 없음, 동시 변경). 그 차를 이번 바퀴에서 뺀다 */
    CAR_FAILED
}

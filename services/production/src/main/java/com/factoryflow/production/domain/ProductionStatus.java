package com.factoryflow.production.domain;

/** 생산 건 상태. SAGA 상태이기도 하다 (docs/specs/saga-order-fulfillment.md). */
public enum ProductionStatus {
    /** 생산 건을 만들고 inventory 에 부품 예약을 요청한 상태. 예약 결과를 기다린다 */
    PLANNED,
    /** 부품이 부족해 입고를 기다리는 상태. 불량으로 재생산을 기다릴 때도 여기로 돌아온다 */
    WAITING_MATERIAL,
    /** 부품이 모두 예약되어 생산을 시작할 수 있는 상태 */
    READY,
    /** 생산 라인에서 만드는 중 (started_at 기록). 이후에는 취소할 수 없다 */
    IN_PROGRESS,
    /** 생산 완료 (completed_at 기록) */
    COMPLETED,
    /** 생산 시작 전에 주문이 취소된 상태. 예약한 부품은 해제된다 */
    CANCELLED
}

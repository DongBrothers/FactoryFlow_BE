package com.factoryflow.production.domain;

/** 생산 건 상태. SAGA 상태이기도 하다 (docs/specs/saga-order-fulfillment.md). */
public enum ProductionStatus {
    /** 생산 건을 만들고 inventory 에 부품 예약을 요청한 상태. 예약 결과를 기다린다 */
    PLANNED,
    /** 부품이 부족해 입고를 기다리는 상태 */
    WAITING_MATERIAL,
    /** 부품이 모두 예약된 상태. 생산의 트리거로 작업지시가 발행되고 첫 공정을 기다린다 */
    READY,
    /** 첫 공정(프레스)을 시작해 만드는 중 (started_at 기록). 이후에는 취소할 수 없다 */
    IN_PROGRESS,
    /** 검사 완료, VIN 발행 (completed_at 기록) */
    COMPLETED,
    /** 첫 공정 시작 전에 주문이 취소된 상태. 예약한 부품은 해제된다 */
    CANCELLED,
    /** 공정 불량으로 폐기된 상태. 잡아 둔 부품은 폐기 소모되고 같은 주문으로 대체 차가 만들어진다 (docs/specs/defect-scrap.md) */
    SCRAPPED
}

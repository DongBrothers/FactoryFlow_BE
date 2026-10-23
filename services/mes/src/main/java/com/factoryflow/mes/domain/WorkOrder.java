package com.factoryflow.mes.domain;

import com.factoryflow.common.web.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 작업지시: 생산 건(차량 1대)당 1건. production 이 발행하면(production.released) 만든다 (docs/specs/mes-split.md). */
@Getter
@Entity
@Table(name = "work_order")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrder extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long productionId;

    @Column(nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private WorkOrderStatus status;

    /** 차대번호. 검사 완료 때 발행한다 (#73). */
    @Column(length = 17)
    private String vin;

    @Column(nullable = false, updatable = false)
    private LocalDateTime releasedAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    /** 공정이 마지막으로 바뀐 시각. 공정이 바뀔 때마다 작업지시도 바뀌게 해 version 으로 동시 변경을 막는다. */
    private LocalDateTime stepChangedAt;

    /** 같은 작업지시를 동시에 바꾸면(같은 공정을 두 작업자가 시작 등) 늦은 쪽이 실패한다. */
    @Version private Long version;

    private WorkOrder(
            Long productionId,
            Long orderId,
            Long productId,
            WorkOrderStatus status,
            LocalDateTime now) {
        this.productionId = productionId;
        this.orderId = orderId;
        this.productId = productId;
        this.status = status;
        this.releasedAt = now;
    }

    /** production 이 발행한 차(production.released)의 작업지시. */
    public static WorkOrder release(
            Long productionId, Long orderId, Long productId, LocalDateTime now) {
        return new WorkOrder(productionId, orderId, productId, WorkOrderStatus.RELEASED, now);
    }

    /** 발행 이벤트보다 취소 요청이 먼저 왔다: 취소된 작업지시를 남겨 뒤에 온 발행을 건너뛰게 한다. 제품은 아직 몰라 0 이다 (작업지시는 진행되지 않는다). */
    public static WorkOrder cancelBeforeRelease(
            Long productionId, Long orderId, LocalDateTime now) {
        return new WorkOrder(productionId, orderId, 0L, WorkOrderStatus.CANCELLED, now);
    }

    /** 취소되어 더 진행하지 않는 작업지시. */
    /** 취소되거나 폐기돼 더 진행하지 않는 작업지시. 배정·작업자 시작·설비 보고에서 빠진다. */
    public boolean isClosed() {
        return status == WorkOrderStatus.CANCELLED || status == WorkOrderStatus.SCRAPPED;
    }

    /** 공정 불량으로 차를 폐기했다. 끝난 시각을 남긴다 (VIN 없음). */
    public void scrap(LocalDateTime now) {
        this.status = WorkOrderStatus.SCRAPPED;
        this.completedAt = now;
    }

    /** 공정이 바뀌었다 (시작, 종료). 같은 작업지시의 공정을 동시에 바꾸면 늦은 쪽이 version 충돌로 실패한다. */
    public void recordStepChange(LocalDateTime now) {
        this.stepChangedAt = now;
    }

    /**
     * production 의 취소 요청: 첫 공정을 시작하기 전(RELEASED)이면 CANCELLED. 배정·작업자 시작 대상에서 빠진다. 시작했거나 끝났으면 그대로.
     * 바뀌었으면 true.
     */
    public boolean cancel() {
        if (status != WorkOrderStatus.RELEASED) {
            return false;
        }
        this.status = WorkOrderStatus.CANCELLED;
        return true;
    }

    /** 마지막 공정(검사)이 양품으로 끝나 차가 완성됐다: VIN 을 붙이고 COMPLETED. */
    public void complete(String vin, LocalDateTime now) {
        this.status = WorkOrderStatus.COMPLETED;
        this.vin = vin;
        this.completedAt = now;
    }

    /** 첫 공정을 처음 시작했다. RELEASED 에서만 바뀐다 (시간 초과로 되돌린 공정을 다시 시작할 때는 그대로). 바뀌었으면 true. */
    public boolean start(LocalDateTime now) {
        if (status != WorkOrderStatus.RELEASED) {
            return false;
        }
        this.status = WorkOrderStatus.IN_PROGRESS;
        this.startedAt = now;
        return true;
    }
}

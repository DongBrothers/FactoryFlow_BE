package com.factoryflow.production.domain;

import com.factoryflow.common.web.BaseEntity;
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

/**
 * 작업지시: 생산 건(차량 1대)당 1건. 부품 예약이 끝나면(inventory.reserved) 발행한다 (docs/specs/production-work-order.md).
 */
@Getter
@Entity
@Table(name = "work_order")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrder extends BaseEntity {

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

    private WorkOrder(Production production, LocalDateTime now) {
        this.productionId = production.getId();
        this.orderId = production.getOrderId();
        this.productId = production.getProductId();
        this.status = WorkOrderStatus.RELEASED;
        this.releasedAt = now;
    }

    public static WorkOrder release(Production production, LocalDateTime now) {
        return new WorkOrder(production, now);
    }

    /** 취소되었거나 실패해 더 진행하지 않는 작업지시. */
    public boolean isClosed() {
        return status == WorkOrderStatus.CANCELLED || status == WorkOrderStatus.FAILED;
    }

    /** 공정이 바뀌었다 (시작, 종료). 같은 작업지시의 공정을 동시에 바꾸면 늦은 쪽이 version 충돌로 실패한다. */
    public void recordStepChange(LocalDateTime now) {
        this.stepChangedAt = now;
    }

    /** 마지막 공정(검사)이 양품으로 끝나 차가 완성됐다: VIN 을 붙이고 COMPLETED. */
    public void complete(String vin, LocalDateTime now) {
        this.status = WorkOrderStatus.COMPLETED;
        this.vin = vin;
        this.completedAt = now;
    }

    /** 첫 공정을 처음 시작했다. RELEASED 에서만 바뀐다 (불량 재작업으로 다시 시작할 때는 그대로). 바뀌었으면 true. */
    public boolean start(LocalDateTime now) {
        if (status != WorkOrderStatus.RELEASED) {
            return false;
        }
        this.status = WorkOrderStatus.IN_PROGRESS;
        this.startedAt = now;
        return true;
    }
}

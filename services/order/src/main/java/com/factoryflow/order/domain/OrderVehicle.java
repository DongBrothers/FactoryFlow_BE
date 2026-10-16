package com.factoryflow.order.domain;

import com.factoryflow.common.web.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 주문 품목 속 차량 1대 = production 의 생산 건 1건. productionId 는 production 의 생산 건 (논리 참조). */
@Getter
@Entity
@Table(name = "order_vehicle")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderVehicle extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false)
    private Long orderItemId;

    @Column(nullable = false, updatable = false)
    private Long productionId;

    /** 차대번호. 생산이 끝나면(production.completed) 기록한다. */
    @Column(length = 17)
    private String vin;

    /** 취소된 시각 (production.cancelled). */
    private LocalDateTime cancelledAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    private OrderVehicle(Long orderId, Long orderItemId, Long productionId) {
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.productionId = productionId;
        this.status = OrderStatus.RECEIVED;
    }

    /**
     * 생산 건 상태를 차량에 반영한다. 끝난 차량(COMPLETED, SHIPPED, CANCELLED)은 그대로 두고, 취소 결과를 기다리는 차량
     * (CANCEL_REQUESTED)은 CANCELLED 만 받는다. 상태가 바뀌었으면 true.
     */
    public boolean applyProductionStatus(OrderStatus next) {
        if (status.isFinished()) {
            return false;
        }
        if (status == OrderStatus.CANCEL_REQUESTED && next != OrderStatus.CANCELLED) {
            return false;
        }
        if (status == next) {
            return false;
        }
        this.status = next;
        return true;
    }

    /** 생산이 끝났다: COMPLETED 로 바꾸고 VIN 을 기록한다. 바꾸는 규칙은 applyProductionStatus 와 같다. 바뀌었으면 true. */
    public boolean complete(String vin) {
        if (!applyProductionStatus(OrderStatus.COMPLETED)) {
            return false;
        }
        this.vin = vin;
        return true;
    }

    /** 취소를 요청한다. 이미 취소된 차량은 그대로. 바뀌었으면 true. */
    public boolean requestCancel() {
        if (status == OrderStatus.CANCELLED) {
            return false;
        }
        this.status = OrderStatus.CANCEL_REQUESTED;
        return true;
    }

    /** production 이 취소했다. 바뀌었으면 true. */
    public boolean cancel(LocalDateTime now) {
        if (!applyProductionStatus(OrderStatus.CANCELLED)) {
            return false;
        }
        this.cancelledAt = now;
        return true;
    }

    /** production 이 취소를 거절했다: 취소 요청 중이면 거절 때의 상태로 되돌린다. 바뀌었으면 true. */
    public boolean restoreAfterRejection(OrderStatus current) {
        if (status != OrderStatus.CANCEL_REQUESTED) {
            return false;
        }
        this.status = current;
        return true;
    }

    /** since 이후에 취소됐는가 (이번 취소 요청 회차에 취소됐는가). */
    public boolean isCancelledSince(LocalDateTime since) {
        return status == OrderStatus.CANCELLED
                && cancelledAt != null
                && !cancelledAt.isBefore(since);
    }

    public static OrderVehicle receive(Long orderId, Long orderItemId, Long productionId) {
        return new OrderVehicle(orderId, orderItemId, productionId);
    }
}

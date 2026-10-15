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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 생산 건: 주문 속 제품 1종. orderId 는 order 의 주문 (논리 참조). */
@Getter
@Entity
@Table(name = "production")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Production extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ProductionStatus status;

    /** 생산을 처음 시작한 시각. 불량으로 WAITING_MATERIAL 로 돌아가도 남아 있어 재생산 중임을 알 수 있다. */
    private LocalDateTime startedAt;

    private Production(Long orderId, Long productId, int qty) {
        this.orderId = orderId;
        this.productId = productId;
        this.qty = qty;
        this.status = ProductionStatus.PLANNED;
    }

    public static Production plan(Long orderId, Long productId, int qty) {
        return new Production(orderId, productId, qty);
    }

    /** 부품이 모두 예약되면 생산할 수 있다. PLANNED, WAITING_MATERIAL 에서만 바뀐다. 바뀌었으면 true. */
    public boolean markReady() {
        if (status != ProductionStatus.PLANNED && status != ProductionStatus.WAITING_MATERIAL) {
            return false;
        }
        this.status = ProductionStatus.READY;
        return true;
    }

    /** 부품 일부가 입고를 기다린다. PLANNED 에서만 바뀐다. 바뀌었으면 true. */
    public boolean waitForMaterial() {
        if (status != ProductionStatus.PLANNED) {
            return false;
        }
        this.status = ProductionStatus.WAITING_MATERIAL;
        return true;
    }

    public boolean isCancelled() {
        return status == ProductionStatus.CANCELLED;
    }

    /** 한 번 시작했다가 불량으로 되돌아온 생산 건. 이때의 상태 변화는 order 에 알리지 않는다. */
    public boolean isRework() {
        return startedAt != null;
    }
}

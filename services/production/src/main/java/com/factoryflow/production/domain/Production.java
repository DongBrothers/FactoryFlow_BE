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
 * 생산 건: 차량 1대. orderId 는 order 의 주문 (논리 참조), seq 는 그 주문·제품 안의 차량 번호
 * (docs/planning/production-flow.md).
 */
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
    private int seq;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ProductionStatus status;

    /** 첫 공정(프레스)을 시작한 시각. 이후에는 취소할 수 없다. */
    private LocalDateTime startedAt;

    /** 검사 양품으로 완성된 시각 (VIN 발행). */
    private LocalDateTime completedAt;

    /** 같은 생산 건을 동시에 바꾸면(프레스 시작 vs 취소 수신 등) 늦은 쪽이 실패한다. */
    @Version private Long version;

    private Production(Long orderId, Long productId, int seq) {
        this.orderId = orderId;
        this.productId = productId;
        this.seq = seq;
        this.status = ProductionStatus.PLANNED;
    }

    public static Production plan(Long orderId, Long productId, int seq) {
        return new Production(orderId, productId, seq);
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

    /** 첫 공정(프레스)을 시작했다. READY 에서만 바뀐다. 이후에는 취소할 수 없다. 바뀌었으면 true. */
    public boolean start(LocalDateTime now) {
        if (status != ProductionStatus.READY) {
            return false;
        }
        this.status = ProductionStatus.IN_PROGRESS;
        this.startedAt = now;
        return true;
    }

    /** 검사 양품으로 차가 완성됐다. IN_PROGRESS 에서만 바뀐다. 바뀌었으면 true. */
    public boolean complete(LocalDateTime now) {
        if (status != ProductionStatus.IN_PROGRESS) {
            return false;
        }
        this.status = ProductionStatus.COMPLETED;
        this.completedAt = now;
        return true;
    }

    /** 취소되었거나 설비 고장으로 실패해 더 진행하지 않는 생산 건. 늦게 온 예약은 풀어야 한다. */
    public boolean isClosed() {
        return status == ProductionStatus.CANCELLED || status == ProductionStatus.FAILED;
    }
}

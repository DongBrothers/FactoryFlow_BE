package com.factoryflow.production.domain;

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

/**
 * 생산 건: 차량 1대. orderId 는 order 의 주문 (논리 참조), seq 는 그 주문·제품 안의 차량 번호
 * (docs/planning/production-flow.md).
 */
@Getter
@Entity
@Table(name = "production")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Production extends BaseTimeEntity {

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

    /** 작업지시를 발행한 시각. 발행 전이면 NULL. 발행한 뒤의 취소는 mes 가 판단한다 (docs/specs/mes-split.md). */
    private LocalDateTime releasedAt;

    /** 같은 생산 건을 동시에 바꾸면(발행 vs 취소 수신 등) 늦은 쪽이 실패한다. */
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

    /** 주문 뒤 제품이나 BOM 이 사라져 만들 수 없는 차: 처음부터 CANCELLED 인 생산 건 (docs/specs/stalled-flows.md S8). */
    public static Production reject(Long orderId, Long productId, int seq) {
        Production production = new Production(orderId, productId, seq);
        production.status = ProductionStatus.CANCELLED;
        return production;
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

    /** 생산 담당이 작업지시를 발행했다. 상태는 READY 그대로이고, 이후 시작·취소 판단은 mes 가 한다. */
    public void release(LocalDateTime now) {
        this.releasedAt = now;
    }

    public boolean isReleased() {
        return releasedAt != null;
    }

    /** mes 가 첫 공정(프레스)을 시작했다. READY 에서만 바뀐다. 이후에는 취소할 수 없다. 바뀌었으면 true. 시작 시각은 mes 작업지시가 갖는다. */
    public boolean start() {
        if (status != ProductionStatus.READY) {
            return false;
        }
        this.status = ProductionStatus.IN_PROGRESS;
        return true;
    }

    /** 프레스 배정 전(PLANNED, WAITING_MATERIAL, READY)이면 취소한다. 바뀌었으면 true. 배정 후면 false (거절). */
    public boolean cancel() {
        if (status != ProductionStatus.PLANNED
                && status != ProductionStatus.WAITING_MATERIAL
                && status != ProductionStatus.READY) {
            return false;
        }
        this.status = ProductionStatus.CANCELLED;
        return true;
    }

    /**
     * mes 가 검사 양품으로 차를 완성했다. IN_PROGRESS 에서 바뀌고, 시작 알림보다 완성 알림이 먼저 오면 READY 에서도 바뀐다 (큐가 다르다). 바뀌었으면
     * true. 완료 시각은 mes 작업지시가 갖는다.
     */
    /**
     * mes 가 공정 불량으로 차를 폐기했다. IN_PROGRESS 에서 바뀌고, 시작 알림보다 폐기 알림이 먼저 오면 READY 에서도 바뀐다 (큐가 다르다). 바뀌었으면
     * true.
     */
    public boolean scrap() {
        if (status != ProductionStatus.IN_PROGRESS && status != ProductionStatus.READY) {
            return false;
        }
        this.status = ProductionStatus.SCRAPPED;
        return true;
    }

    public boolean complete() {
        if (status != ProductionStatus.IN_PROGRESS && status != ProductionStatus.READY) {
            return false;
        }
        this.status = ProductionStatus.COMPLETED;
        return true;
    }

    /** 취소되어 더 진행하지 않는 생산 건. 늦게 온 예약은 풀어야 한다. */
    public boolean isClosed() {
        return status == ProductionStatus.CANCELLED;
    }
}

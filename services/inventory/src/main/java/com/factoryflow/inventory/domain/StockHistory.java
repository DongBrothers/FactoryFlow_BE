package com.factoryflow.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 재고 이력. 추가만 하고 수정하지 않는다. */
@Getter
@Entity
@Table(name = "stock_history")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long partId;

    @Column(nullable = false, updatable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private StockHistoryType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private StockHistoryRefType refType;

    @Column(nullable = false, updatable = false)
    private Long refId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private StockHistory(
            Long partId, int qty, StockHistoryType type, StockHistoryRefType refType, Long refId) {
        this.partId = partId;
        this.qty = qty;
        this.type = type;
        this.refType = refType;
        this.refId = refId;
    }

    public static StockHistory releaseForProduction(Long partId, int qty, Long productionId) {
        return new StockHistory(
                partId,
                qty,
                StockHistoryType.RELEASE,
                StockHistoryRefType.PRODUCTION,
                productionId);
    }

    /** 재고 조정. 늘렸으면 ADJUST_IN, 줄였으면 ADJUST_OUT 이고 qty 는 그 크기 (항상 양수). */
    public static StockHistory adjust(StockAdjustment adjustment) {
        return new StockHistory(
                adjustment.getPartId(),
                Math.abs(adjustment.getQty()),
                adjustment.isIncrease() ? StockHistoryType.ADJUST_IN : StockHistoryType.ADJUST_OUT,
                StockHistoryRefType.ADJUSTMENT,
                adjustment.getId());
    }

    public static StockHistory receiveFromPurchase(Long partId, int qty, Long purchaseId) {
        return new StockHistory(
                partId, qty, StockHistoryType.RECEIVE, StockHistoryRefType.PURCHASE, purchaseId);
    }

    /** 생산 건이 잡아 둔 부품을 썼다: 완성(CONSUME) 또는 폐기(SCRAP). */
    public static StockHistory useForProduction(
            StockHistoryType type, Long partId, int qty, Long productionId) {
        return new StockHistory(partId, qty, type, StockHistoryRefType.PRODUCTION, productionId);
    }

    public static StockHistory reserveForProduction(Long partId, int qty, Long productionId) {
        return new StockHistory(
                partId,
                qty,
                StockHistoryType.RESERVE,
                StockHistoryRefType.PRODUCTION,
                productionId);
    }
}

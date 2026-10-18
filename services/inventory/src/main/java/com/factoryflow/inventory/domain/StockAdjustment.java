package com.factoryflow.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 재고 조정: 창고 실물에 맞춰 바로 쓸 수 있는 수량을 늘리거나(qty 양수) 줄인다(음수). 추가만 하고 수정하지 않는다
 * (docs/specs/stock-adjustment.md).
 */
@Getter
@Entity
@Table(name = "stock_adjustment")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long partId;

    @Column(nullable = false, updatable = false)
    private int qty;

    @Column(nullable = false, updatable = false)
    private String reason;

    /** 조정한 직원 public_id (gateway 의 X-User-Id). */
    @Column(nullable = false, updatable = false, length = 36)
    private String adjustedBy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private StockAdjustment(Long partId, int qty, String reason, String adjustedBy) {
        this.partId = partId;
        this.qty = qty;
        this.reason = reason;
        this.adjustedBy = adjustedBy;
    }

    public static StockAdjustment record(Long partId, int qty, String reason, String adjustedBy) {
        return new StockAdjustment(partId, qty, reason, adjustedBy);
    }

    public boolean isIncrease() {
        return qty > 0;
    }
}

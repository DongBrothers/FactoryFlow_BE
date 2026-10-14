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

    private Production(Long orderId, Long productId, int qty) {
        this.orderId = orderId;
        this.productId = productId;
        this.qty = qty;
        this.status = ProductionStatus.PLANNED;
    }

    public static Production plan(Long orderId, Long productId, int qty) {
        return new Production(orderId, productId, qty);
    }
}

package com.factoryflow.inventory.domain;

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

/** 생산 건이 쓸 부품 예약 줄. productionId 는 production 의 생산 건 (논리 참조). id 순서가 대기 순서다. */
@Getter
@Entity
@Table(name = "part_reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartReservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long productionId;

    @Column(nullable = false, updatable = false)
    private Long partId;

    @Column(nullable = false, updatable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    private PartReservation(Long productionId, Long partId, int qty, ReservationStatus status) {
        this.productionId = productionId;
        this.partId = partId;
        this.qty = qty;
        this.status = status;
    }

    public static PartReservation reserved(Long productionId, Long partId, int qty) {
        return new PartReservation(productionId, partId, qty, ReservationStatus.RESERVED);
    }

    public static PartReservation waiting(Long productionId, Long partId, int qty) {
        return new PartReservation(productionId, partId, qty, ReservationStatus.WAITING);
    }
}

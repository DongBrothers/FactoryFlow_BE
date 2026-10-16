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

/** 완성차 재고: 차 한 대 (VIN). productionId, orderId, productId 는 다른 서비스의 id (논리 참조). */
@Getter
@Entity
@Table(name = "finished_vehicle")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinishedVehicle extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 17)
    private String vin;

    @Column(nullable = false, updatable = false)
    private Long productionId;

    @Column(nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private FinishedVehicleStatus status;

    private FinishedVehicle(String vin, Long productionId, Long orderId, Long productId) {
        this.vin = vin;
        this.productionId = productionId;
        this.orderId = orderId;
        this.productId = productId;
        this.status = FinishedVehicleStatus.IN_STOCK;
    }

    /** 생산이 끝난 차를 완성차 재고로 넣는다. */
    public static FinishedVehicle stock(
            String vin, Long productionId, Long orderId, Long productId) {
        return new FinishedVehicle(vin, productionId, orderId, productId);
    }
}

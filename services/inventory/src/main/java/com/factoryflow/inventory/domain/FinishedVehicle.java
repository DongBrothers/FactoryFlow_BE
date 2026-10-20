package com.factoryflow.inventory.domain;

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
 * 완성차 재고: 차 한 대 (VIN). productionId, orderId, productId 는 다른 서비스의 id (논리 참조). 같은 차를 동시에 출고하면
 * version 으로 막는다.
 */
@Getter
@Entity
@Table(name = "finished_vehicle")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinishedVehicle extends BaseTimeEntity {

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

    /**
     * 출고장 자리 (예: A-01). 자리 대기면 null. 출고한 뒤에도 어디서 나갔는지 남는다
     * (docs/specs/finished-vehicle-location.md).
     */
    @Column(length = 10)
    private String slotCode;

    private LocalDateTime shippedAt;

    @Version
    @Column(nullable = false)
    private Long version;

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

    /** 출고장 자리에 섰다 ({@link StorageSlot#park} 에서 부른다). */
    void parkAt(String slotCode) {
        this.slotCode = slotCode;
    }

    /** 창고에 있는 차를 출고한다. 이미 출고됐으면 바꾸지 않는다. 바뀌었으면 true. */
    public boolean ship(LocalDateTime now) {
        if (status != FinishedVehicleStatus.IN_STOCK) {
            return false;
        }
        this.status = FinishedVehicleStatus.SHIPPED;
        this.shippedAt = now;
        return true;
    }
}

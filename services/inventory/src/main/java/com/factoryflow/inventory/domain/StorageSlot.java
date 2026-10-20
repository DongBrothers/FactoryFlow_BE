package com.factoryflow.inventory.domain;

import com.factoryflow.common.web.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 완성차 출고장의 자리 하나 (예: A-01). 자리는 마이그레이션으로 넣고 바꾸지 않는다. 한 자리에 완성차 한 대, 두 차가 같은 빈 자리를 동시에 고르면 version
 * 으로 늦은 쪽이 실패한다 (docs/specs/finished-vehicle-location.md).
 */
@Getter
@Entity
@Table(name = "storage_slot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorageSlot extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 10)
    private String code;

    @Column(nullable = false, updatable = false, length = 10)
    private String zone;

    /** 지금 서 있는 완성차. 비었으면 null. */
    private Long finishedVehicleId;

    @Version
    @Column(nullable = false)
    private Long version;

    /** 빈 자리에 완성차를 세운다. */
    public void park(FinishedVehicle vehicle) {
        this.finishedVehicleId = vehicle.getId();
        vehicle.parkAt(code);
    }

    /** 차가 나가 자리를 비운다. */
    public void vacate() {
        this.finishedVehicleId = null;
    }
}

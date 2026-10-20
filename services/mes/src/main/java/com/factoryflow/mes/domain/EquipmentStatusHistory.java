package com.factoryflow.mes.domain;

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

/** 설비 상태 이력. 추가만 하고 수정하지 않는다 (OEE 데이터). changedAt 은 설비 상태가 바뀐 시각이다. */
@Getter
@Entity
@Table(name = "equipment_status_history")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 30)
    private String equipmentCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private EquipmentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private EquipmentStatus toStatus;

    @Column(nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private EquipmentStatusHistory(
            String equipmentCode,
            EquipmentStatus fromStatus,
            EquipmentStatus toStatus,
            LocalDateTime changedAt) {
        this.equipmentCode = equipmentCode;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedAt = changedAt;
    }

    public static EquipmentStatusHistory record(
            String equipmentCode,
            EquipmentStatus fromStatus,
            EquipmentStatus toStatus,
            LocalDateTime changedAt) {
        return new EquipmentStatusHistory(equipmentCode, fromStatus, toStatus, changedAt);
    }
}

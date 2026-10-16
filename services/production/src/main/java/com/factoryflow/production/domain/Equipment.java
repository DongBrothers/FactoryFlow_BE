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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 설비 (예: 프레스기 1호). 공정마다 여러 대이고, code 는 simulator 와 같은 값이다. 기본 데이터는 Flyway 로 넣는다. */
@Getter
@Entity
@Table(name = "equipment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Equipment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private ProductionProcess process;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private EquipmentStatus status;

    /** 지금 맡은 공정(배정·진행 중). 비었으면 null. 같은 설비를 두 차가 동시에 잡으면 version 충돌로 한쪽만 성공한다. */
    private Long occupiedStepId;

    @Version private Long version;

    public boolean isRunning() {
        return status == EquipmentStatus.RUNNING;
    }

    public boolean isOccupied() {
        return occupiedStepId != null;
    }

    /** 공정을 맡는다 (배정 또는 작업자 시작). 비어 있는지는 서비스가 먼저 확인한다. */
    public void occupy(Long stepId) {
        this.occupiedStepId = stepId;
    }

    /** 상태가 바뀌었다 (equipment.status-changed). 같은 설비를 동시에 바꾸면 version 으로 한쪽만 성공한다. */
    public void changeStatus(EquipmentStatus status) {
        this.status = status;
    }

    /** 맡은 공정이 끝났거나(양품 완료, 불량 재작업) 설비가 멈춰 공정을 되돌렸다. */
    public void release() {
        this.occupiedStepId = null;
    }
}

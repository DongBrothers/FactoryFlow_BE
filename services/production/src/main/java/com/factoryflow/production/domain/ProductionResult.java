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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 공정 실적: 공정을 끝낼 때마다 한 줄. workerId 는 작업자 public_id (gateway 의 X-User-Id), 설비 보고(로봇 공정)면 null. */
@Getter
@Entity
@Table(name = "production_result")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductionResult extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long workOrderStepId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private StepResult result;

    @Column(updatable = false)
    private String defectReason;

    @Column(updatable = false, length = 36)
    private String workerId;

    @Column(nullable = false, updatable = false, length = 30)
    private String equipmentCode;

    @Column(nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime endedAt;

    private ProductionResult(
            WorkOrderStep step,
            StepResult result,
            String defectReason,
            String workerId,
            LocalDateTime now) {
        this.workOrderStepId = step.getId();
        this.result = result;
        this.defectReason = defectReason;
        this.workerId = workerId;
        this.equipmentCode = step.getEquipmentCode();
        this.startedAt = step.getStartedAt();
        this.endedAt = now;
    }

    /** 진행 중인 공정의 실적. 공정의 설비와 시작 시각을 그대로 남긴다 (공정을 끝내기 전에 만든다). */
    public static ProductionResult record(
            WorkOrderStep step,
            StepResult result,
            String defectReason,
            String workerId,
            LocalDateTime now) {
        return new ProductionResult(step, result, defectReason, workerId, now);
    }
}

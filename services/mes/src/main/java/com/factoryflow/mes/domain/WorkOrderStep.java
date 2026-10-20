package com.factoryflow.mes.domain;

import com.factoryflow.common.web.BaseTimeEntity;
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

/** 작업지시의 공정 하나. 라우팅 순서(seq)대로만 진행한다. */
@Getter
@Entity
@Table(name = "work_order_step")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrderStep extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long workOrderId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private ProductionProcess process;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private StepStatus status;

    /** 배정되었거나 진행 중인 설비. 고장·정지 나면 이 설비의 공정만 되돌려 다른 설비로 재배정한다. */
    @Column(length = 30)
    private String equipmentCode;

    /** 설비에 배정한 시각. 시작 보고 없이 오래 지나면 되돌린다. */
    private LocalDateTime dispatchedAt;

    /** 배정 회차 번호. 공정을 맡길 때마다(로봇 배정, 작업자 시작) +1. 설비 보고의 회차가 다르면 지난 배정의 보고다. */
    @Column(nullable = false)
    private int dispatchSeq;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private WorkOrderStep(Long workOrderId, ProductionProcess process) {
        this.workOrderId = workOrderId;
        this.process = process;
        this.seq = process.seq();
        this.status = StepStatus.PENDING;
    }

    public static WorkOrderStep plan(Long workOrderId, ProductionProcess process) {
        return new WorkOrderStep(workOrderId, process);
    }

    public boolean isPending() {
        return status == StepStatus.PENDING;
    }

    public boolean isDispatched() {
        return status == StepStatus.DISPATCHED;
    }

    public boolean isInProgress() {
        return status == StepStatus.IN_PROGRESS;
    }

    public boolean isDone() {
        return status == StepStatus.DONE;
    }

    /** 설비에서 공정을 시작한다. 시작 가능 여부는 서비스가 먼저 확인한다. */
    public void start(String equipmentCode, LocalDateTime now) {
        this.dispatchSeq++;
        this.dispatchedAt = null;
        this.status = StepStatus.IN_PROGRESS;
        this.equipmentCode = equipmentCode;
        this.startedAt = now;
        this.endedAt = null;
    }

    /** 로봇 공정을 설비에 배정한다. 설비의 시작 보고를 기다린다. */
    public void dispatch(String equipmentCode, LocalDateTime now) {
        this.dispatchSeq++;
        this.status = StepStatus.DISPATCHED;
        this.equipmentCode = equipmentCode;
        this.dispatchedAt = now;
        this.startedAt = null;
        this.endedAt = null;
    }

    /** 배정받은 설비가 시작을 보고했다. */
    public void startByEquipment(LocalDateTime now) {
        this.status = StepStatus.IN_PROGRESS;
        this.startedAt = now;
    }

    /** 이 회차(dispatchSeq)의 설비 보고인가. 회차가 없는 보고(이 기능 전 메시지)는 회차로 거르지 않는다. */
    public boolean isCurrentDispatch(Integer reportedSeq) {
        return reportedSeq == null || reportedSeq == dispatchSeq;
    }

    /**
     * 설비가 진행 중인데(배정으로 시작) deadline 전에 시작해 완료 보고 없이 시간이 지났는가. 작업자가 시작한 공정은 dispatchedAt 이 없어 아니다.
     */
    public boolean isEquipmentRunStartedBefore(LocalDateTime deadline) {
        return isInProgress()
                && dispatchedAt != null
                && startedAt != null
                && startedAt.isBefore(deadline);
    }

    /** 배정 뒤 시작 보고 없이 시간이 지났는가. */
    public boolean isDispatchedBefore(LocalDateTime deadline) {
        return isDispatched() && dispatchedAt != null && dispatchedAt.isBefore(deadline);
    }

    /** 설비가 고장·정지해 중단됐다: 시작 전(PENDING)으로 돌아가 다른 설비로 다시 진행한다. 실적은 남기지 않는다. */
    public void interrupt() {
        this.status = StepStatus.PENDING;
        this.equipmentCode = null;
        this.startedAt = null;
    }

    /** 양품이면 끝(DONE), 불량이면 재작업을 위해 시작 전(PENDING)으로 돌아간다. */
    public void finish(StepResult result, LocalDateTime now) {
        if (result == StepResult.GOOD) {
            this.status = StepStatus.DONE;
            this.endedAt = now;
            return;
        }
        this.status = StepStatus.PENDING;
        this.equipmentCode = null;
        this.startedAt = null;
    }
}

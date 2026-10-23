package com.factoryflow.mes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 의장 공정에서 작업자가 찍은 부품 LOT. 작업지시의 부품마다 하나 (docs/specs/part-lot-scan.md). */
@Getter
@Entity
@Table(name = "work_order_part_scan")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrderPartScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long workOrderId;

    @Column(nullable = false, updatable = false)
    private Long partId;

    @Column(nullable = false, updatable = false, length = 32)
    private String lotNo;

    @Column(nullable = false, updatable = false, length = 36)
    private String scannedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime scannedAt;

    private WorkOrderPartScan(
            Long workOrderId, Long partId, String lotNo, String scannedBy, LocalDateTime now) {
        this.workOrderId = workOrderId;
        this.partId = partId;
        this.lotNo = lotNo;
        this.scannedBy = scannedBy;
        this.scannedAt = now;
    }

    public static WorkOrderPartScan record(
            Long workOrderId, Long partId, String lotNo, String scannedBy, LocalDateTime now) {
        return new WorkOrderPartScan(workOrderId, partId, lotNo, scannedBy, now);
    }
}

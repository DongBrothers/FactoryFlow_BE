package com.factoryflow.simulator.domain;

import lombok.Getter;

/** 가상 설비 한 대 (메모리). 상태와 지금 하는 작업(없거나 하나)을 갖는다. 동기화는 EquipmentService 가 한다. */
@Getter
public class VirtualEquipment {

    private final String code;
    private final LineProcess process;
    private EquipmentStatus status;
    private Job job;

    public VirtualEquipment(String code, LineProcess process) {
        this.code = code;
        this.process = process;
        this.status = EquipmentStatus.RUNNING;
    }

    /** 배정을 받을 수 있는가: 가동 중이고 비어 있다. */
    public boolean canTake() {
        return status == EquipmentStatus.RUNNING && job == null;
    }

    public void take(Job job) {
        this.job = job;
    }

    /** 이 작업을 아직 하고 있는가 (고장으로 버렸거나 이미 끝났으면 false). */
    public boolean isWorkingOn(Job job) {
        return status == EquipmentStatus.RUNNING && this.job == job;
    }

    public void finish() {
        this.job = null;
    }

    /** 상태를 바꾼다. 멈추면(FAULT, STOPPED) 하던 작업을 버린다. 바뀌었으면 true. */
    public boolean changeStatus(EquipmentStatus status) {
        if (this.status == status) {
            return false;
        }
        this.status = status;
        if (status != EquipmentStatus.RUNNING) {
            this.job = null;
        }
        return true;
    }
}

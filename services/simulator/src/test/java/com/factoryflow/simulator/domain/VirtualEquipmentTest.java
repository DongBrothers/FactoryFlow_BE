package com.factoryflow.simulator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 설비 상태 바꾸기: 멈추면 작업을 버리고, 상태가 바뀔 때마다 회차가 오른다 (자동 수리가 자기 고장만 풀게). */
class VirtualEquipmentTest {

    @Test
    void stoppingDropsTheJobAndEveryChangeIsANewStatusRound() {
        VirtualEquipment equipment = new VirtualEquipment("PRESS-01", LineProcess.PRESS);
        equipment.take(new Job(1L, LineProcess.PRESS, "t", 1));
        long start = equipment.getStatusRound();

        assertThat(equipment.changeStatus(EquipmentStatus.FAULT)).isTrue();
        long firstFault = equipment.getStatusRound();
        assertThat(equipment.getJob()).isNull();
        assertThat(equipment.changeStatus(EquipmentStatus.FAULT)).isFalse();
        assertThat(equipment.getStatusRound()).isEqualTo(firstFault);

        // 수동으로 가동했다가 다시 고장 내면 다른 고장이다. 첫 고장의 자동 수리는 이 고장을 풀면 안 된다
        equipment.changeStatus(EquipmentStatus.RUNNING);
        equipment.changeStatus(EquipmentStatus.FAULT);

        assertThat(firstFault).isGreaterThan(start);
        assertThat(equipment.isInFaultRound(firstFault)).isFalse();
        assertThat(equipment.isInFaultRound(equipment.getStatusRound())).isTrue();
    }

    @Test
    void manualProcessEquipmentNeverTakesWork() {
        assertThat(new VirtualEquipment("ASSEMBLY-01", LineProcess.ASSEMBLY).canTake()).isFalse();
        assertThat(new VirtualEquipment("PRESS-01", LineProcess.PRESS).canTake()).isTrue();
    }
}

package com.factoryflow.inventory.domain;

/** 차에 들어간 LOT 을 어떻게 정했는지 (docs/specs/part-lot-scan.md). */
public enum PartLotUsageSource {
    /** 의장 공정에서 작업자가 찍은 LOT (확실) */
    SCANNED,
    /** 먼저 들어온 LOT 부터 배정 (추정) */
    FIFO
}

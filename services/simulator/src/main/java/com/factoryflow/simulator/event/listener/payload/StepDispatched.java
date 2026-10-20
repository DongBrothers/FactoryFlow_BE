package com.factoryflow.simulator.event.listener.payload;

import com.factoryflow.simulator.domain.LineProcess;

/** 받은 mes.step-dispatched 의 payload (docs/specs/events.md). dispatchSeq 는 배정 회차로 보고에 그대로 돌려준다. */
public record StepDispatched(
        Long workOrderId, LineProcess process, String equipmentCode, Integer dispatchSeq) {}

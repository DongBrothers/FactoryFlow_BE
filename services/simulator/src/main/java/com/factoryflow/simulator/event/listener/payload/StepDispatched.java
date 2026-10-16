package com.factoryflow.simulator.event.listener.payload;

import com.factoryflow.simulator.domain.LineProcess;

/** 받은 production.step-dispatched 의 payload (docs/specs/events.md). */
public record StepDispatched(Long workOrderId, LineProcess process, String equipmentCode) {}

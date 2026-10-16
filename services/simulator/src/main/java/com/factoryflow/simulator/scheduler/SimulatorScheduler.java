package com.factoryflow.simulator.scheduler;

import com.factoryflow.simulator.config.SimulatorProperties;
import com.factoryflow.simulator.event.publisher.EquipmentEventPublisher;
import com.factoryflow.simulator.service.EquipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 확인 못 받은 메시지 재전송과 자동 고장을 주기적으로 돌린다. */
@Component
@RequiredArgsConstructor
public class SimulatorScheduler {

    private final EquipmentEventPublisher publisher;
    private final EquipmentService equipmentService;
    private final SimulatorProperties properties;

    @Scheduled(fixedDelayString = "${factoryflow.simulator.resend-interval:1s}")
    public void resendPending() {
        publisher.resendPending(properties.resendInterval());
    }

    @Scheduled(fixedDelayString = "${factoryflow.simulator.fault-check-interval:30s}")
    public void rollFaults() {
        equipmentService.rollFaults();
    }
}

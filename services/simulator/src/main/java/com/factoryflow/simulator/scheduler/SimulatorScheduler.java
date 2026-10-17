package com.factoryflow.simulator.scheduler;

import com.factoryflow.simulator.config.SimulatorProperties;
import com.factoryflow.simulator.event.publisher.EquipmentEventPublisher;
import com.factoryflow.simulator.service.EquipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 시작할 때 설비 상태 알림, 확인 못 받은 메시지 재전송과 자동 고장을 주기적으로 돌린다. */
@Component
@RequiredArgsConstructor
public class SimulatorScheduler {

    private final EquipmentEventPublisher publisher;
    private final EquipmentService equipmentService;
    private final SimulatorProperties properties;

    /** 재시작하면 설비가 모두 RUNNING 으로 시작한다. production 이 아는 상태를 맞추려고 바로 알린다. */
    @EventListener(ApplicationReadyEvent.class)
    public void announceStatusesOnStartup() {
        equipmentService.announceStatuses();
    }

    @Scheduled(fixedDelayString = "${factoryflow.simulator.resend-interval:1s}")
    public void resendPending() {
        publisher.resendPending(properties.resendInterval());
    }

    @Scheduled(fixedDelayString = "${factoryflow.simulator.fault-check-interval:30s}")
    public void rollFaults() {
        equipmentService.rollFaults();
    }
}

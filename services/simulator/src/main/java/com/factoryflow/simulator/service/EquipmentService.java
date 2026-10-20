package com.factoryflow.simulator.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.simulator.config.SimulatorProperties;
import com.factoryflow.simulator.domain.EquipmentStatus;
import com.factoryflow.simulator.domain.Job;
import com.factoryflow.simulator.domain.LineProcess;
import com.factoryflow.simulator.domain.SeenEvents;
import com.factoryflow.simulator.domain.StepResult;
import com.factoryflow.simulator.domain.VirtualEquipment;
import com.factoryflow.simulator.dto.response.InternalEquipmentResponse;
import com.factoryflow.simulator.event.listener.payload.StepDispatched;
import com.factoryflow.simulator.event.publisher.EquipmentEventPublisher;
import com.factoryflow.simulator.event.publisher.payload.EquipmentStatusChanged;
import com.factoryflow.simulator.event.publisher.payload.EquipmentStepCompleted;
import com.factoryflow.simulator.event.publisher.payload.EquipmentStepStarted;
import com.factoryflow.simulator.exception.SimulatorErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * 가상 설비 10대 (메모리, docs/specs/simulator.md). 배정을 받아 시간을 두고 시작·완료를 보고하고, 상태(가동·정지·고장)를 바꿔 알린다. 업무 판단은
 * 하지 않는다. 메서드는 설비 상태를 함께 바꾸므로 synchronized 다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentService {

    private final SimulatorProperties properties;
    private final EquipmentEventPublisher publisher;
    private final TaskScheduler taskScheduler;
    private final RandomGenerator random;
    private final Clock clock;
    private final Map<String, VirtualEquipment> fleet = createFleet();
    private final SeenEvents seen = new SeenEvents();

    /** 공정마다 설비 수. mes 의 기본 설비 데이터(V2)와 같아야 한다. */
    private static final int EQUIPMENT_PER_PROCESS = 2;

    /** mes 의 equipment 와 같은 코드: {공정}-{두 자리 번호} (예: PRESS-01). */
    private static Map<String, VirtualEquipment> createFleet() {
        Map<String, VirtualEquipment> fleet = new LinkedHashMap<>();
        for (LineProcess process : LineProcess.values()) {
            for (int n = 1; n <= EQUIPMENT_PER_PROCESS; n++) {
                String code = String.format("%s-%02d", process.name(), n);
                fleet.put(code, new VirtualEquipment(code, process));
            }
        }
        return fleet;
    }

    /**
     * mes.step-dispatched: 같은 메시지는 한 번만. 설비가 없거나, 사람 공정 설비이거나, 가동 중이 아니거나, 다른 작업 중이면 무시한다 (mes 가 시작
     * 보고 시간 초과로 되돌린다). 맡으면 시작 지연 뒤 시작 보고를 보낸다.
     */
    public synchronized void acceptDispatch(UUID eventId, String traceId, StepDispatched dispatch) {
        if (!seen.markIfNew(eventId, properties.seenEventLimit())) {
            log.info("이미 받은 배정이라 무시: eventId={}", eventId);
            return;
        }
        VirtualEquipment equipment = fleet.get(dispatch.equipmentCode());
        if (equipment == null || !equipment.canTake()) {
            log.info(
                    "배정을 받을 수 없어 무시: equipment={} workOrderId={} status={}",
                    dispatch.equipmentCode(),
                    dispatch.workOrderId(),
                    equipment == null ? null : equipment.getStatus());
            return;
        }
        Job job =
                new Job(
                        dispatch.workOrderId(),
                        dispatch.process(),
                        traceId,
                        dispatch.dispatchSeq());
        equipment.take(job);
        later(properties.startDelay(), () -> reportStarted(equipment, job));
    }

    private synchronized void reportStarted(VirtualEquipment equipment, Job job) {
        if (!equipment.isWorkingOn(job)) {
            return;
        }
        publisher.publish(
                job.traceId(),
                new EquipmentStepStarted(
                        equipment.getCode(), job.workOrderId(), job.process(), job.dispatchSeq()));
        later(properties.workTimeOf(job.process()), () -> reportCompleted(equipment, job));
    }

    /** 작업 시간이 지났다. 고장·정지로 작업을 버렸으면 보고하지 않는다. 불량 확률로 DEFECT. */
    private synchronized void reportCompleted(VirtualEquipment equipment, Job job) {
        if (!equipment.isWorkingOn(job)) {
            return;
        }
        boolean defect = random.nextDouble() < properties.defectRate();
        equipment.finish();
        publisher.publish(
                job.traceId(),
                new EquipmentStepCompleted(
                        equipment.getCode(),
                        job.workOrderId(),
                        job.process(),
                        defect ? StepResult.DEFECT : StepResult.GOOD,
                        defect ? job.process().defectReason() : null,
                        job.dispatchSeq()));
    }

    /** 설비 상태를 바꾼다 (내부 API, 자동 고장). 같은 상태면 그대로. 멈추면 하던 작업을 버린다 (보고 안 함). */
    public synchronized InternalEquipmentResponse changeStatus(
            String code, EquipmentStatus status) {
        VirtualEquipment equipment = findEquipment(code);
        if (equipment.changeStatus(status)) {
            publisher.publish(
                    UUID.randomUUID().toString(),
                    new EquipmentStatusChanged(code, equipment.getProcess(), status));
        }
        return InternalEquipmentResponse.from(equipment);
    }

    /**
     * 설비마다 지금 상태를 알린다. simulator 가 시작할 때 mes 가 고장·정지로 알던 설비를 맞추려고 보낸다 (설비가 기준,
     * docs/specs/simulator-restart.md).
     */
    public synchronized void announceStatuses() {
        for (VirtualEquipment equipment : fleet.values()) {
            publisher.publish(
                    UUID.randomUUID().toString(),
                    new EquipmentStatusChanged(
                            equipment.getCode(), equipment.getProcess(), equipment.getStatus()));
        }
    }

    /**
     * 자동 고장: 가동 중인 설비마다 faultRate 확률로 고장, repairTime 뒤 가동. 그사이 수동으로 가동했다가 다시 고장 냈으면 다른 고장이므로 풀지 않는다
     * (상태 회차로 구분).
     */
    public synchronized void rollFaults() {
        for (VirtualEquipment equipment : fleet.values()) {
            if (equipment.getStatus() == EquipmentStatus.RUNNING
                    && random.nextDouble() < properties.faultRate()) {
                changeStatus(equipment.getCode(), EquipmentStatus.FAULT);
                long round = equipment.getStatusRound();
                later(properties.repairTime(), () -> repair(equipment, round));
            }
        }
    }

    private synchronized void repair(VirtualEquipment equipment, long round) {
        if (equipment.isInFaultRound(round)) {
            changeStatus(equipment.getCode(), EquipmentStatus.RUNNING);
        }
    }

    public synchronized List<InternalEquipmentResponse> findEquipments() {
        return fleet.values().stream().map(InternalEquipmentResponse::from).toList();
    }

    private VirtualEquipment findEquipment(String code) {
        VirtualEquipment equipment = fleet.get(code);
        if (equipment == null) {
            throw new CustomException(SimulatorErrorCode.EQUIPMENT_NOT_FOUND);
        }
        return equipment;
    }

    private void later(Duration delay, Runnable task) {
        taskScheduler.schedule(task, clock.instant().plus(delay));
    }
}

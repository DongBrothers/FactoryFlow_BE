package com.factoryflow.mes.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.mes.domain.Equipment;
import com.factoryflow.mes.domain.EquipmentStatus;
import com.factoryflow.mes.domain.EquipmentStatusHistory;
import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.dto.response.EquipmentResponse;
import com.factoryflow.mes.dto.response.EquipmentStatusHistoryResponse;
import com.factoryflow.mes.exception.MesErrorCode;
import com.factoryflow.mes.repository.EquipmentRepository;
import com.factoryflow.mes.repository.EquipmentStatusHistoryRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 설비. 상태는 simulator 의 equipment.status-changed 로 바뀐다 (docs/specs/equipment-status.md). */
@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentService {

    private final EquipmentRepository equipmentRepository;
    private final EquipmentStatusHistoryRepository historyRepository;
    private final WorkOrderService workOrderService;

    /** 한 공정의 설비와 상태. 작업자가 공정을 시작할 설비를 고를 때 쓴다. */
    @Transactional(readOnly = true)
    public List<EquipmentResponse> findEquipments(ProductionProcess process) {
        return findEquipmentsOf(process).stream().map(EquipmentResponse::from).toList();
    }

    /**
     * 설비 상태 이력, 최신 먼저(id 내림차순). 처음엔 lastId 없이 가장 최신부터, 다음 페이지는 nextCursor 를 lastId 로 받아 그보다 작은 id
     * 부터. 없거나 삭제된 설비면 EQUIPMENT_NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public CursorPage<EquipmentStatusHistoryResponse> findHistory(
            String equipmentCode, Long lastId, int size) {
        findEquipment(equipmentCode);
        long beforeId = lastId == null ? Long.MAX_VALUE : lastId;
        CursorPage<EquipmentStatusHistory> page =
                CursorPage.of(
                        findHistoryBefore(equipmentCode, beforeId, size + 1),
                        size,
                        EquipmentStatusHistory::getId);
        return new CursorPage<>(
                page.items().stream().map(EquipmentStatusHistoryResponse::from).toList(),
                page.count(),
                page.last(),
                page.nextCursor());
    }

    /**
     * 설비 상태를 바꾸고 이력을 남긴다(changedAt = 상태가 바뀐 시각). 같은 상태면 아무것도 하지 않는다. 고장·정지면 그 설비가 맡은 공정을 시작 전으로
     * 되돌리고 설비를 비운다: 같은 공정의 다른 설비로 이어서 진행하게 하려는 것이고, 생산 건·작업지시·주문 상태는 바꾸지 않는다 (생산 실패 없음).
     */
    @Transactional
    public void changeStatus(String equipmentCode, EquipmentStatus status, Instant occurredAt) {
        Equipment equipment = findEquipment(equipmentCode);
        EquipmentStatus from = equipment.getStatus();
        if (from == status) {
            log.info("설비 상태가 같아 그대로 둠: equipment={} status={}", equipmentCode, status);
            return;
        }
        equipment.changeStatus(status);
        historyRepository.save(
                EquipmentStatusHistory.record(
                        equipmentCode,
                        from,
                        status,
                        LocalDateTime.ofInstant(occurredAt, ZoneId.systemDefault())));
        if (!equipment.isRunning() && equipment.isOccupied()) {
            log.info(
                    "설비가 멈춰 맡은 공정을 되돌림: equipment={} status={} stepId={}",
                    equipmentCode,
                    status,
                    equipment.getOccupiedStepId());
            workOrderService.interruptStep(equipment.getOccupiedStepId());
            equipment.release();
        }
    }

    private Equipment findEquipment(String equipmentCode) {
        return equipmentRepository
                .findByCodeAndIsDeletedFalse(equipmentCode)
                .orElseThrow(() -> new CustomException(MesErrorCode.EQUIPMENT_NOT_FOUND));
    }

    private List<EquipmentStatusHistory> findHistoryBefore(
            String equipmentCode, long beforeId, int limit) {
        return historyRepository.findAllByEquipmentCodeAndIdLessThanOrderByIdDesc(
                equipmentCode, beforeId, Limit.of(limit));
    }

    private List<Equipment> findEquipmentsOf(ProductionProcess process) {
        return equipmentRepository.findAllByProcessAndIsDeletedFalseOrderByCodeAsc(process);
    }
}

package com.factoryflow.production.service;

import com.factoryflow.production.domain.Equipment;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.dto.response.EquipmentResponse;
import com.factoryflow.production.repository.EquipmentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 설비. 상태는 simulator 의 equipment.status-changed 로 바뀐다 (#74). */
@Service
@RequiredArgsConstructor
public class EquipmentService {

    private final EquipmentRepository equipmentRepository;

    /** 한 공정의 설비와 상태. 작업자가 공정을 시작할 설비를 고를 때 쓴다. */
    @Transactional(readOnly = true)
    public List<EquipmentResponse> findEquipments(ProductionProcess process) {
        return findEquipmentsOf(process).stream().map(EquipmentResponse::from).toList();
    }

    private List<Equipment> findEquipmentsOf(ProductionProcess process) {
        return equipmentRepository.findAllByProcessAndIsDeletedFalseOrderByCodeAsc(process);
    }
}

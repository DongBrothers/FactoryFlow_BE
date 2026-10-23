package com.factoryflow.mes.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.mes.domain.WorkOrder;
import com.factoryflow.mes.domain.WorkOrderPartScan;
import com.factoryflow.mes.dto.response.LotVehicleResponse;
import com.factoryflow.mes.dto.response.VehiclePartLotResponse;
import com.factoryflow.mes.exception.MesErrorCode;
import com.factoryflow.mes.repository.WorkOrderPartScanRepository;
import com.factoryflow.mes.repository.WorkOrderRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 부품 계보: 의장에서 찍은 LOT 으로 VIN ↔ LOT 을 답한다 (docs/specs/part-genealogy.md). */
@Service
@RequiredArgsConstructor
public class PartScanService {

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderPartScanRepository workOrderPartScanRepository;

    /** 그 차에 찍은 LOT, 부품 id 순. VIN 의 작업지시가 없으면 WORK_ORDER_NOT_FOUND. */
    @Transactional(readOnly = true)
    public List<VehiclePartLotResponse> findPartLotsOfVehicle(String vin) {
        WorkOrder workOrder = findWorkOrderByVin(vin);
        return findScans(workOrder.getId()).stream().map(VehiclePartLotResponse::from).toList();
    }

    /** 그 LOT 을 찍은 차, 찍은 시각 순. 검사 전 차(VIN 없음)도 들어간다 (리콜 대상). 모르는 LOT 이면 빈 목록. */
    @Transactional(readOnly = true)
    public List<LotVehicleResponse> findVehiclesOfLot(String lotNo) {
        List<WorkOrderPartScan> scans = findScansOfLot(lotNo);
        Map<Long, WorkOrder> workOrders =
                findWorkOrders(scans.stream().map(WorkOrderPartScan::getWorkOrderId).toList());
        return scans.stream()
                .map(scan -> LotVehicleResponse.of(scan, workOrders.get(scan.getWorkOrderId())))
                .toList();
    }

    private WorkOrder findWorkOrderByVin(String vin) {
        return workOrderRepository
                .findByVin(vin)
                .orElseThrow(() -> new CustomException(MesErrorCode.WORK_ORDER_NOT_FOUND));
    }

    private List<WorkOrderPartScan> findScans(Long workOrderId) {
        return workOrderPartScanRepository.findAllByWorkOrderIdOrderByPartIdAsc(workOrderId);
    }

    private List<WorkOrderPartScan> findScansOfLot(String lotNo) {
        return workOrderPartScanRepository.findAllByLotNoOrderByScannedAtAscIdAsc(lotNo);
    }

    private Map<Long, WorkOrder> findWorkOrders(List<Long> workOrderIds) {
        return workOrderRepository.findAllById(workOrderIds).stream()
                .collect(Collectors.toMap(WorkOrder::getId, Function.identity()));
    }
}

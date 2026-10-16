package com.factoryflow.inventory.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.domain.FinishedVehicle;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartLot;
import com.factoryflow.inventory.domain.PartLotUsage;
import com.factoryflow.inventory.dto.response.LotVehicleResponse;
import com.factoryflow.inventory.dto.response.VehiclePartLotResponse;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.FinishedVehicleRepository;
import com.factoryflow.inventory.repository.PartLotRepository;
import com.factoryflow.inventory.repository.PartLotUsageRepository;
import com.factoryflow.inventory.repository.PartRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 부품 LOT 추적 조회: VIN → 쓰인 LOT, LOT → 쓰인 차 (docs/specs/part-lot.md). */
@Service
@RequiredArgsConstructor
public class PartLotService {

    private final FinishedVehicleRepository finishedVehicleRepository;
    private final PartLotUsageRepository partLotUsageRepository;
    private final PartLotRepository partLotRepository;
    private final PartRepository partRepository;

    /** 그 차에 쓰인 부품 LOT. 부품 코드 순, 같은 부품은 LOT 없음 먼저 그다음 입고 순. 완성차가 없으면 FINISHED_VEHICLE_NOT_FOUND. */
    @Transactional(readOnly = true)
    public List<VehiclePartLotResponse> findPartLotsOfVehicle(String vin) {
        if (!isStocked(vin)) {
            throw new CustomException(InventoryErrorCode.FINISHED_VEHICLE_NOT_FOUND);
        }
        List<PartLotUsage> usages = findUsagesOfVehicle(vin);
        Map<Long, Part> parts = findParts(usages.stream().map(PartLotUsage::getPartId).toList());
        Map<Long, PartLot> lots =
                findLots(
                        usages.stream()
                                .map(PartLotUsage::getLotId)
                                .filter(Objects::nonNull)
                                .toList());
        return usages.stream()
                .sorted(
                        Comparator.comparing((PartLotUsage u) -> parts.get(u.getPartId()).getCode())
                                .thenComparing(
                                        PartLotUsage::getLotId,
                                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(
                        u ->
                                VehiclePartLotResponse.of(
                                        u, parts.get(u.getPartId()), lots.get(u.getLotId())))
                .toList();
    }

    /** 그 LOT 의 부품이 들어간 차, 사용 기록 순. LOT 이 없으면 PART_LOT_NOT_FOUND. */
    @Transactional(readOnly = true)
    public List<LotVehicleResponse> findVehiclesOfLot(String lotNo) {
        PartLot lot = findLotOrThrow(lotNo);
        Part part = findPartOrThrow(lot.getPartId());
        List<PartLotUsage> usages = findUsagesOfLot(lot.getId());
        Map<String, FinishedVehicle> vehicles =
                findVehicles(usages.stream().map(PartLotUsage::getVin).toList());
        return usages.stream()
                .map(u -> LotVehicleResponse.of(vehicles.get(u.getVin()), part, u))
                .toList();
    }

    private boolean isStocked(String vin) {
        return finishedVehicleRepository.existsByVinAndIsDeletedFalse(vin);
    }

    private List<PartLotUsage> findUsagesOfVehicle(String vin) {
        return partLotUsageRepository.findAllByVinOrderByIdAsc(vin);
    }

    private List<PartLotUsage> findUsagesOfLot(Long lotId) {
        return partLotUsageRepository.findAllByLotIdOrderByIdAsc(lotId);
    }

    private Map<Long, Part> findParts(Collection<Long> partIds) {
        return partRepository.findAllById(partIds).stream()
                .collect(Collectors.toMap(Part::getId, Function.identity()));
    }

    private Part findPartOrThrow(Long partId) {
        return partRepository
                .findById(partId)
                .orElseThrow(() -> new CustomException(InventoryErrorCode.PART_NOT_FOUND));
    }

    private Map<Long, PartLot> findLots(Collection<Long> lotIds) {
        return partLotRepository.findAllByIdIn(lotIds).stream()
                .collect(Collectors.toMap(PartLot::getId, Function.identity()));
    }

    private PartLot findLotOrThrow(String lotNo) {
        return partLotRepository
                .findByLotNoAndIsDeletedFalse(lotNo)
                .orElseThrow(() -> new CustomException(InventoryErrorCode.PART_LOT_NOT_FOUND));
    }

    private Map<String, FinishedVehicle> findVehicles(Collection<String> vins) {
        return finishedVehicleRepository.findAllByVinIn(vins).stream()
                .collect(Collectors.toMap(FinishedVehicle::getVin, Function.identity()));
    }
}

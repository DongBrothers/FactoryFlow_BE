package com.factoryflow.inventory.service;

import com.factoryflow.inventory.domain.FinishedVehicle;
import com.factoryflow.inventory.domain.FinishedVehicleStatus;
import com.factoryflow.inventory.domain.StorageSlot;
import com.factoryflow.inventory.dto.response.StorageSlotListResponse;
import com.factoryflow.inventory.dto.response.StorageSlotResponse;
import com.factoryflow.inventory.repository.FinishedVehicleRepository;
import com.factoryflow.inventory.repository.StorageSlotRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 완성차 출고장 자리: 입고 때 배정, 출고 때 비움, 현황 (docs/specs/finished-vehicle-location.md). */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageSlotService {

    private final StorageSlotRepository storageSlotRepository;
    private final FinishedVehicleRepository finishedVehicleRepository;

    /**
     * 창고에 들어온 차를 빈 자리 중 코드 순 첫 자리에 세운다. 빈 자리가 없으면 자리 없이 둔다 (자리 대기, 완성은 실패시키지 않는다). 완성차 입고와 같은
     * 트랜잭션에서 부른다.
     */
    @Transactional
    public void park(FinishedVehicle vehicle) {
        findFirstFreeSlot()
                .ifPresentOrElse(
                        slot -> slot.park(vehicle),
                        () -> log.warn("자리 없음: vin={}", vehicle.getVin()));
    }

    /**
     * 출고한 차가 서 있던 자리를 비우고, 자리를 기다리는 가장 먼저 완성된 차를 그 자리에 세운다. 자리 없이 있던 차면 아무것도 하지 않는다. 출고와 같은 트랜잭션에서
     * 부른다.
     */
    @Transactional
    public void vacate(FinishedVehicle shipped) {
        findSlotOf(shipped.getId())
                .ifPresent(
                        slot -> {
                            slot.vacate();
                            findOldestWaitingVehicle().ifPresent(slot::park);
                        });
    }

    /** 자리 현황: 40자리를 코드 순으로, 서 있는 차의 VIN, 차가 선 자리 수(자리 기준), 자리를 기다리는 차 수. */
    @Transactional(readOnly = true)
    public StorageSlotListResponse findSlots() {
        List<StorageSlot> slots = findAllSlots();
        Map<Long, String> vinsById =
                findVehicles(
                                slots.stream()
                                        .map(StorageSlot::getFinishedVehicleId)
                                        .filter(id -> id != null)
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(FinishedVehicle::getId, FinishedVehicle::getVin));
        List<StorageSlotResponse> items =
                slots.stream()
                        .map(s -> StorageSlotResponse.of(s, vinsById.get(s.getFinishedVehicleId())))
                        .toList();
        int occupied = (int) slots.stream().filter(s -> s.getFinishedVehicleId() != null).count();
        return StorageSlotListResponse.of(items, occupied, countWaitingVehicles());
    }

    private Optional<StorageSlot> findFirstFreeSlot() {
        return storageSlotRepository.findFirstByFinishedVehicleIdIsNullOrderByCodeAsc();
    }

    private Optional<StorageSlot> findSlotOf(Long finishedVehicleId) {
        return storageSlotRepository.findByFinishedVehicleId(finishedVehicleId);
    }

    private Optional<FinishedVehicle> findOldestWaitingVehicle() {
        return finishedVehicleRepository.findFirstByStatusAndSlotCodeIsNullOrderByIdAsc(
                FinishedVehicleStatus.IN_STOCK);
    }

    private List<StorageSlot> findAllSlots() {
        return storageSlotRepository.findAllByOrderByCodeAsc();
    }

    private List<FinishedVehicle> findVehicles(List<Long> ids) {
        return finishedVehicleRepository.findAllById(ids);
    }

    private long countWaitingVehicles() {
        return finishedVehicleRepository.countByStatusAndSlotCodeIsNull(
                FinishedVehicleStatus.IN_STOCK);
    }
}

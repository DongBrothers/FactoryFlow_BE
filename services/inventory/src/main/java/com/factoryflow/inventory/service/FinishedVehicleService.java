package com.factoryflow.inventory.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.domain.FinishedVehicle;
import com.factoryflow.inventory.domain.FinishedVehicleStatus;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartLot;
import com.factoryflow.inventory.domain.PartReservation;
import com.factoryflow.inventory.domain.ReservationStatus;
import com.factoryflow.inventory.domain.StockHistory;
import com.factoryflow.inventory.dto.response.FinishedVehicleListResponse;
import com.factoryflow.inventory.dto.response.FinishedVehicleSummaryResponse;
import com.factoryflow.inventory.dto.response.ShipFinishedVehicleResponse;
import com.factoryflow.inventory.event.listener.payload.ProductionCompleted;
import com.factoryflow.inventory.event.listener.payload.ScannedPartLot;
import com.factoryflow.inventory.event.publisher.payload.ShipmentCompleted;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.FinishedVehicleRepository;
import com.factoryflow.inventory.repository.PartLotRepository;
import com.factoryflow.inventory.repository.PartRepository;
import com.factoryflow.inventory.repository.PartReservationRepository;
import com.factoryflow.inventory.repository.StockHistoryRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 완성차 재고와 생산에 쓴 부품 사용 처리 (docs/specs/production-completion.md), 완성차 출고 (docs/specs/shipment.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinishedVehicleService {

    private final FinishedVehicleRepository finishedVehicleRepository;
    private final PartReservationRepository reservationRepository;
    private final PartRepository partRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final PartLotRepository partLotRepository;
    private final StorageSlotService storageSlotService;
    private final EventPublisher eventPublisher;

    /**
     * production.completed: 그 생산 건의 RESERVED 예약 줄을 모두 CONSUMED 로 바꾸고(reserved −qty, available 그대로,
     * stock_history CONSUME, 쓴 부품 LOT 기록. 의장 공정에서 찍은 LOT 이 있으면 그 LOT 부터,
     * docs/specs/part-lot-scan.md) 완성차 재고를 한 대 넣고 출고장 빈 자리에 세운다. 이미 완성차가 있으면 건너뛰고, RESERVED 줄이 없으면
     * RESERVATION_NOT_FOUND (완성된 차는 부품이 모두 잡혀 있었다).
     */
    @Transactional
    public void stock(ProductionCompleted event) {
        if (isStocked(event.productionId())) {
            log.info("완성차가 이미 있어 건너뜀: productionId={}", event.productionId());
            return;
        }
        List<PartReservation> reservations = findReserved(event.productionId());
        if (reservations.isEmpty()) {
            throw new CustomException(InventoryErrorCode.RESERVATION_NOT_FOUND);
        }
        consume(event.productionId(), event.vin(), reservations, event.scannedLotsOrEmpty());
        FinishedVehicle vehicle =
                finishedVehicleRepository.save(
                        FinishedVehicle.stock(
                                event.vin(),
                                event.productionId(),
                                event.orderId(),
                                event.productId()));
        storageSlotService.park(vehicle);
    }

    /**
     * 창고에 있는 완성차 한 대를 출고한다: SHIPPED + shipped_at, 자리를 비우고 기다리던 차를 세운 뒤 shipment.completed. 없으면
     * FINISHED_VEHICLE_NOT_FOUND, 이미 출고됐으면 FINISHED_VEHICLE_ALREADY_SHIPPED, 다른 요청이 먼저 바꿨으면
     * FINISHED_VEHICLE_CONFLICT.
     */
    @Transactional
    public ShipFinishedVehicleResponse ship(String vin) {
        FinishedVehicle vehicle = findVehicleOrThrow(vin);
        if (!vehicle.ship(LocalDateTime.now())) {
            throw new CustomException(InventoryErrorCode.FINISHED_VEHICLE_ALREADY_SHIPPED);
        }
        storageSlotService.vacate(vehicle);
        flushOrConflict(vin);
        eventPublisher.publish(
                new ShipmentCompleted(
                        vehicle.getOrderId(),
                        vehicle.getProductionId(),
                        vehicle.getProductId(),
                        vehicle.getVin()));
        return ShipFinishedVehicleResponse.from(vehicle);
    }

    /**
     * 완성차 목록, 최신 먼저 size 개. status 가 있으면 그 상태만. lastVin 이 있으면 그 차보다 오래된 것부터 (밖으로는 VIN 만 쓰고 안에서 id 로
     * 바꾼다). 다음 페이지가 있는지 알려고 하나 더 읽는다 (docs/specs/list-apis.md).
     */
    @Transactional(readOnly = true)
    public FinishedVehicleListResponse findFinishedVehicles(
            String status, String lastVin, int size) {
        Long beforeId = lastVin == null ? Long.MAX_VALUE : findVehicleOrThrow(lastVin).getId();
        List<FinishedVehicleSummaryResponse> rows =
                findVehiclesBefore(status, beforeId, size + 1).stream()
                        .map(FinishedVehicleSummaryResponse::from)
                        .toList();
        return FinishedVehicleListResponse.of(rows, size);
    }

    private List<FinishedVehicle> findVehiclesBefore(String status, Long beforeId, int limit) {
        if (status == null) {
            return finishedVehicleRepository.findAllByIdLessThanOrderByIdDesc(
                    beforeId, Limit.of(limit));
        }
        return finishedVehicleRepository.findAllByStatusAndIdLessThanOrderByIdDesc(
                FinishedVehicleStatus.valueOf(status), beforeId, Limit.of(limit));
    }

    /** 이벤트를 보내기 전에 저장해 version 을 확인한다. 다른 요청이 먼저 바꿨으면 FINISHED_VEHICLE_CONFLICT. */
    private void flushOrConflict(String vin) {
        try {
            finishedVehicleRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.info("다른 작업이 먼저 바꿔 출고 거절: vin={}", vin);
            throw new CustomException(InventoryErrorCode.FINISHED_VEHICLE_CONFLICT);
        }
    }

    private FinishedVehicle findVehicleOrThrow(String vin) {
        return finishedVehicleRepository
                .findByVin(vin)
                .orElseThrow(
                        () -> new CustomException(InventoryErrorCode.FINISHED_VEHICLE_NOT_FOUND));
    }

    /**
     * 예약 줄마다 LOT 남은 수량을 줄이고, 부품의 잡은 수량을 줄이고 사용 이력을 남긴다. 찍은 LOT 을 쓸 수 있으면 그 LOT 에서, 아니면 입고 순으로 뺀다.
     * 어느 차에 어느 LOT 이 들어갔는지(계보)는 mes 가 갖는다 (docs/specs/part-genealogy.md). 부품과 LOT 은 한 번에 읽는다 (동시
     * 예약·입고와 부품 version 으로 부딪힌다).
     */
    private void consume(
            Long productionId,
            String vin,
            List<PartReservation> reservations,
            List<ScannedPartLot> scans) {
        List<Long> partIds = reservations.stream().map(PartReservation::getPartId).toList();
        Map<Long, Part> partsById = findParts(partIds);
        Map<Long, List<PartLot>> lotsByPartId = findOpenLots(partIds);
        Map<Long, PartLot> scannedByPartId = scannedLotsByPart(vin, scans, partIds);
        for (PartReservation reservation : reservations) {
            Part part = partsById.get(reservation.getPartId());
            if (!takeScanned(vin, scannedByPartId.get(part.getId()), reservation.getQty())) {
                takeInReceiptOrder(
                        productionId,
                        part,
                        reservation.getQty(),
                        lotsByPartId.getOrDefault(part.getId(), List.of()));
            }
            part.consume(reservation.getQty());
            reservation.consume();
            stockHistoryRepository.save(
                    StockHistory.consumeForProduction(
                            reservation.getPartId(), reservation.getQty(), productionId));
        }
    }

    /**
     * 찍은 LOT 중 쓸 수 있는 것을 부품별로. 차에 없는 부품, 없는 LOT, 다른 부품의 LOT 은 빼고 WARN 을 남긴다 (그 부품은 입고 순으로 뺀다, 차 완성은
     * 막지 않는다).
     */
    private Map<Long, PartLot> scannedLotsByPart(
            String vin, List<ScannedPartLot> scans, List<Long> partIds) {
        if (scans.isEmpty()) {
            return Map.of();
        }
        Map<String, PartLot> lotsByNo =
                findLotsByNo(scans.stream().map(ScannedPartLot::lotNo).toList());
        Map<Long, PartLot> byPartId = new HashMap<>();
        for (ScannedPartLot scan : scans) {
            PartLot lot = lotsByNo.get(scan.lotNo());
            String reason = rejectReason(scan, lot, partIds);
            if (reason == null) {
                byPartId.put(scan.partId(), lot);
            } else {
                logRejected(vin, scan.partId(), scan.lotNo(), reason);
            }
        }
        return byPartId;
    }

    private static String rejectReason(ScannedPartLot scan, PartLot lot, List<Long> partIds) {
        if (!partIds.contains(scan.partId())) {
            return "NOT_IN_VEHICLE";
        }
        if (lot == null) {
            return "NOT_FOUND";
        }
        return lot.getPartId().equals(scan.partId()) ? null : "OTHER_PART";
    }

    /** 찍은 LOT 에 qty 가 다 남아 있으면 거기서 뺀다. 아니면 false (입고 순으로 뺀다). */
    private boolean takeScanned(String vin, PartLot scanned, int qty) {
        if (scanned == null) {
            return false;
        }
        if (scanned.getRemainingQty() < qty) {
            logRejected(vin, scanned.getPartId(), scanned.getLotNo(), "NOT_ENOUGH");
            return false;
        }
        scanned.take(qty);
        return true;
    }

    private static void logRejected(String vin, Long partId, String lotNo, String reason) {
        log.warn(
                "SCANNED_LOT_REJECTED vin={} partId={} lotNo={} reason={} (입고 순으로 뺌)",
                vin,
                partId,
                lotNo,
                reason);
    }

    /**
     * 부품 qty 를 LOT 에서 입고 순서로 뺀다 (docs/specs/part-lot.md "2. 생산 완료", 초기 재고 LOT 이 가장 먼저다). LOT 이 모자라면
     * 실물과 LOT 이 어긋난 데이터라 PART_LOT_SHORTAGE (재시도 후 DLQ).
     */
    private void takeInReceiptOrder(Long productionId, Part part, int qty, List<PartLot> lots) {
        int left = qty;
        for (PartLot lot : lots) {
            left -= lot.take(left);
            if (left == 0) {
                return;
            }
        }
        log.error(
                "PART_LOT_SHORTAGE productionId={} partId={} missing={}",
                productionId,
                part.getId(),
                left);
        throw new CustomException(InventoryErrorCode.PART_LOT_SHORTAGE);
    }

    /** 찍은 LOT 번호의 LOT 을 번호별로. 없는 번호는 빠진다. */
    private Map<String, PartLot> findLotsByNo(List<String> lotNos) {
        return partLotRepository.findAllByLotNoIn(lotNos).stream()
                .collect(Collectors.toMap(PartLot::getLotNo, Function.identity()));
    }

    /** 그 부품들의 아직 쓰지 않은 LOT 을 부품별로, 입고 순서대로. */
    private Map<Long, List<PartLot>> findOpenLots(List<Long> partIds) {
        return partLotRepository
                .findAllByPartIdInAndRemainingQtyGreaterThanOrderByReceivedAtAscIdAsc(partIds, 0)
                .stream()
                .collect(Collectors.groupingBy(PartLot::getPartId));
    }

    private boolean isStocked(Long productionId) {
        return finishedVehicleRepository.existsByProductionId(productionId);
    }

    private List<PartReservation> findReserved(Long productionId) {
        return reservationRepository.findAllByProductionIdAndStatus(
                productionId, ReservationStatus.RESERVED);
    }

    /** 잡아 둔 부품을 한 번에 읽는다. 그사이 삭제된 부품이 있으면 PART_NOT_FOUND (원인을 알 수 있게, 재시도 후 DLQ). */
    private Map<Long, Part> findParts(List<Long> partIds) {
        Map<Long, Part> partsById =
                partRepository.findAllByIdInAndIsDeletedFalse(partIds).stream()
                        .collect(Collectors.toMap(Part::getId, Function.identity()));
        if (partsById.size() != partIds.stream().distinct().count()) {
            throw new CustomException(InventoryErrorCode.PART_NOT_FOUND);
        }
        return partsById;
    }
}

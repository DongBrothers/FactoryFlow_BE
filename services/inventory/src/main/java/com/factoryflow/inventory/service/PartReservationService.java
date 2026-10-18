package com.factoryflow.inventory.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartLot;
import com.factoryflow.inventory.domain.PartReservation;
import com.factoryflow.inventory.domain.ReservationStatus;
import com.factoryflow.inventory.domain.StockHistory;
import com.factoryflow.inventory.domain.StockHistoryRefType;
import com.factoryflow.inventory.domain.StockHistoryType;
import com.factoryflow.inventory.event.listener.payload.MaterialRequestedPart;
import com.factoryflow.inventory.event.listener.payload.PurchaseReceived;
import com.factoryflow.inventory.event.publisher.payload.InventoryReserved;
import com.factoryflow.inventory.event.publisher.payload.ReservationDeferred;
import com.factoryflow.inventory.event.publisher.payload.ReservationFailed;
import com.factoryflow.inventory.event.publisher.payload.ReservationShortage;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.PartLotRepository;
import com.factoryflow.inventory.repository.PartRepository;
import com.factoryflow.inventory.repository.PartReservationRepository;
import com.factoryflow.inventory.repository.StockHistoryRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartReservationService {

    private final PartRepository partRepository;
    private final PartReservationRepository reservationRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final PartLotRepository partLotRepository;
    private final EventPublisher eventPublisher;

    /**
     * 생산 건에 필요한 부품을 partId 순서로 예약한다 (docs/specs/inventory-reservation.md). 같은 부품에 기다리는 줄이 없고 재고가
     * 충분하면 RESERVED, 아니면 WAITING 으로 뒤에 선다. 생산 건 결과는 inventory.reserved 또는
     * inventory.reservation-pending 으로 알린다. 부족한 부품은 구매 담당이 부족 부품 조회로 보고 직접 구매한다
     * (docs/specs/purchase-manual.md). 이미 예약 줄이 있는 생산 건은 건너뛴다. 없거나 삭제된 부품이 있으면 아무것도 잡지 않고
     * inventory.reservation-failed (docs/specs/stalled-flows.md S18).
     */
    @Transactional
    public void reserve(Long productionId, List<MaterialRequestedPart> parts) {
        if (reservationRepository.existsByProductionId(productionId)) {
            log.info("예약 줄이 이미 있어 건너뜀: productionId={}", productionId);
            return;
        }
        Map<Long, Integer> qtyByPartId = mergeByPartId(parts);
        Map<Long, Part> partsById = findActiveParts(qtyByPartId.keySet());
        if (partsById.size() != qtyByPartId.size()) {
            failReservation(productionId, qtyByPartId.keySet(), partsById.keySet());
            return;
        }
        Map<Long, Integer> waitingQtyByPartId = waitingQtyByPartId(qtyByPartId.keySet());
        List<ReservationShortage> shortages = new ArrayList<>();
        qtyByPartId.forEach(
                (partId, qty) ->
                        reserveOne(productionId, partsById.get(partId), qty, waitingQtyByPartId)
                                .ifPresent(shortages::add));
        publishResult(productionId, shortages);
    }

    /**
     * purchase.received: 재고를 늘리고(RECEIVE 이력, LOT 한 줄) 그 부품을 기다리는 줄을 id 순서(먼저 기다린 순서)로 채운다. 앞 줄이
     * 들어가지 않으면 거기서 멈춘다 (뒤의 작은 줄이 들어갈 수 있어도 새치기하지 않는다). 줄이 다 찬 생산 건은 inventory.reserved. 같은 구매 건의
     * 입고는 한 번만 반영한다 (docs/specs/purchase-receiving.md).
     */
    @Transactional
    public void receive(PurchaseReceived event) {
        if (isReceived(event.purchaseId())) {
            log.info("이미 입고한 구매 건이라 건너뜀: purchaseId={}", event.purchaseId());
            return;
        }
        Part part = loadParts(Set.of(event.partId())).get(event.partId());
        part.receive(event.qty());
        stockHistoryRepository.save(
                StockHistory.receiveFromPurchase(part.getId(), event.qty(), event.purchaseId()));
        partLotRepository.save(
                PartLot.receive(
                        part.getId(), event.purchaseId(), event.qty(), LocalDateTime.now()));
        announceReserved(fillWaitingLines(part));
    }

    /**
     * production.cancelled: 그 생산 건의 예약 줄을 푼다. RESERVED 줄은 재고를 돌려놓고(available +, reserved −, RELEASE
     * 이력), WAITING 줄은 그냥 푼다. 풀린 부품은 그 부품을 기다리던 줄에 먼저 온 순서로 넘기고, 다 찬 생산 건은 inventory.reserved. 이미
     * 풀렸거나 줄이 없으면 아무것도 하지 않는다 (docs/specs/order-cancel.md).
     */
    @Transactional
    public void release(Long productionId) {
        List<PartReservation> lines = findOpenLines(productionId);
        if (lines.isEmpty()) {
            log.info("풀 예약 줄이 없어 건너뜀: productionId={}", productionId);
            return;
        }
        List<PartReservation> reserved =
                lines.stream().filter(PartReservation::isReserved).toList();
        Map<Long, Part> partsById =
                loadParts(
                        reserved.stream()
                                .map(PartReservation::getPartId)
                                .collect(Collectors.toSet()));
        for (PartReservation line : reserved) {
            partsById.get(line.getPartId()).release(line.getQty());
            stockHistoryRepository.save(
                    StockHistory.releaseForProduction(
                            line.getPartId(), line.getQty(), productionId));
        }
        lines.forEach(PartReservation::release);
        List<Long> filled = new ArrayList<>();
        partsById.values().forEach(part -> filled.addAll(fillWaitingLines(part)));
        announceReserved(filled);
    }

    /**
     * 재고 조정으로 바로 쓸 수 있는 수량이 늘었을 때: 입고와 같은 규칙으로 그 부품을 기다리는 줄을 채우고, 다 찬 생산 건은 inventory.reserved
     * (docs/specs/stock-adjustment.md). part 는 부르는 쪽 트랜잭션에서 예약과 같은 잠금으로 읽은 것이다.
     */
    @Transactional
    public void fillWaitingLinesAfterRestock(Part part) {
        announceReserved(fillWaitingLines(part));
    }

    /** 줄을 채운 생산 건 중 기다리는 줄이 더 없는 것만 inventory.reserved. */
    private void announceReserved(List<Long> filled) {
        Set<Long> stillWaiting = findStillWaiting(filled);
        filled.stream()
                .distinct()
                .filter(productionId -> !stillWaiting.contains(productionId))
                .forEach(
                        productionId ->
                                eventPublisher.publish(new InventoryReserved(productionId)));
    }

    private List<PartReservation> findOpenLines(Long productionId) {
        return reservationRepository.findAllByProductionIdAndStatusIn(
                productionId, List.of(ReservationStatus.RESERVED, ReservationStatus.WAITING));
    }

    /** 기다리는 줄을 먼저 온 순서로 RESERVED 로 바꾼다. 들어가지 않는 줄을 만나면 멈춘다. 채운 줄의 생산 건 id 를 돌려준다. */
    private List<Long> fillWaitingLines(Part part) {
        List<Long> filled = new ArrayList<>();
        for (PartReservation line : findWaitingLines(part.getId())) {
            if (part.getAvailable() < line.getQty()) {
                break;
            }
            part.reserve(line.getQty());
            line.reserve();
            stockHistoryRepository.save(
                    StockHistory.reserveForProduction(
                            part.getId(), line.getQty(), line.getProductionId()));
            filled.add(line.getProductionId());
        }
        return filled;
    }

    private boolean isReceived(Long purchaseId) {
        return stockHistoryRepository.existsByTypeAndRefTypeAndRefId(
                StockHistoryType.RECEIVE, StockHistoryRefType.PURCHASE, purchaseId);
    }

    /** 채운 생산 건 중 다른 부품을 아직 기다리는(WAITING 줄이 남은) 생산 건. 생산 건마다 묻지 않고 한 번에 읽는다. */
    private Set<Long> findStillWaiting(List<Long> productionIds) {
        if (productionIds.isEmpty()) {
            return Set.of();
        }
        return reservationRepository
                .findAllByProductionIdInAndStatus(productionIds, ReservationStatus.WAITING)
                .stream()
                .map(PartReservation::getProductionId)
                .collect(Collectors.toSet());
    }

    private List<PartReservation> findWaitingLines(Long partId) {
        return reservationRepository.findAllByPartIdAndStatusOrderByIdAsc(
                partId, ReservationStatus.WAITING);
    }

    /** 같은 부품 줄은 수량을 합치고, partId 순서로 처리하도록 정렬한다. */
    private static Map<Long, Integer> mergeByPartId(List<MaterialRequestedPart> parts) {
        return parts.stream()
                .collect(
                        Collectors.toMap(
                                MaterialRequestedPart::partId,
                                MaterialRequestedPart::qty,
                                Integer::sum,
                                TreeMap::new));
    }

    /** 없거나 삭제된 부품(id 순)을 알리고 아무것도 잡지 않는다. 처리 오류로 던지지 않아 DLQ 로 가지 않는다. */
    private void failReservation(Long productionId, Set<Long> requested, Set<Long> found) {
        List<Long> missing = requested.stream().filter(id -> !found.contains(id)).sorted().toList();
        log.warn("부품이 없어 예약할 수 없음: productionId={} partIds={}", productionId, missing);
        eventPublisher.publish(
                new ReservationFailed(productionId, ReservationFailed.PART_NOT_FOUND, missing));
    }

    /** 요청한 부품 중 있는 것을 한 번에 읽는다 (예약과 같은 version 올림). */
    private Map<Long, Part> findActiveParts(Set<Long> partIds) {
        return partRepository.findAllByIdInAndIsDeletedFalse(partIds).stream()
                .collect(Collectors.toMap(Part::getId, Function.identity()));
    }

    /** 요청한 부품을 한 번에 읽는다. 하나라도 없거나 삭제됐으면 PART_NOT_FOUND. */
    private Map<Long, Part> loadParts(Set<Long> partIds) {
        Map<Long, Part> partsById = findActiveParts(partIds);
        if (partsById.size() != partIds.size()) {
            throw new CustomException(InventoryErrorCode.PART_NOT_FOUND);
        }
        return partsById;
    }

    /** 부품별로 이미 기다리는(WAITING) 수량의 합. 기다리는 줄이 없는 부품은 빠진다. */
    private Map<Long, Integer> waitingQtyByPartId(Set<Long> partIds) {
        return reservationRepository
                .findAllByPartIdInAndStatus(partIds, ReservationStatus.WAITING)
                .stream()
                .collect(
                        Collectors.toMap(
                                PartReservation::getPartId, PartReservation::getQty, Integer::sum));
    }

    /** 기다리는 줄이 없고 재고가 충분하면 RESERVED 로 재고를 잡고 empty 를 돌려준다. 아니면 WAITING 줄을 만들고 그 줄을 돌려준다. */
    private Optional<ReservationShortage> reserveOne(
            Long productionId, Part part, int qty, Map<Long, Integer> waitingQtyByPartId) {
        int waiting = waitingQtyByPartId.getOrDefault(part.getId(), 0);
        if (waiting == 0 && part.getAvailable() >= qty) {
            part.reserve(qty);
            reservationRepository.save(PartReservation.reserved(productionId, part.getId(), qty));
            stockHistoryRepository.save(
                    StockHistory.reserveForProduction(part.getId(), qty, productionId));
            return Optional.empty();
        }
        reservationRepository.save(PartReservation.waiting(productionId, part.getId(), qty));
        return Optional.of(new ReservationShortage(part.getId(), qty));
    }

    private void publishResult(Long productionId, List<ReservationShortage> shortages) {
        if (shortages.isEmpty()) {
            eventPublisher.publish(new InventoryReserved(productionId));
        } else {
            eventPublisher.publish(new ReservationDeferred(productionId, shortages));
        }
    }
}

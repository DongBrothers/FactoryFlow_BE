package com.factoryflow.inventory.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartLot;
import com.factoryflow.inventory.domain.PartReservation;
import com.factoryflow.inventory.domain.ReservationStatus;
import com.factoryflow.inventory.domain.StockAdjustment;
import com.factoryflow.inventory.domain.StockHistory;
import com.factoryflow.inventory.dto.request.AdjustStockRequest;
import com.factoryflow.inventory.dto.response.AdjustStockResponse;
import com.factoryflow.inventory.event.publisher.payload.InventoryReserved;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.PartLotRepository;
import com.factoryflow.inventory.repository.PartRepository;
import com.factoryflow.inventory.repository.PartReservationRepository;
import com.factoryflow.inventory.repository.StockAdjustmentRepository;
import com.factoryflow.inventory.repository.StockHistoryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 재고 조정 저장. production 호출(찜한 부품 거둬들이기)은 StockAdjustmentService 가 트랜잭션 밖에서 끝낸다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockAdjustmentWriter {

    private final PartRepository partRepository;
    private final PartReservationRepository reservationRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final PartLotRepository partLotRepository;
    private final PartReservationService partReservationService;
    private final EventPublisher eventPublisher;

    /**
     * 바로 쓸 수 있는 수량을 qty 만큼 늘리거나(조정 LOT, 기다리는 줄 채움) 줄인다(LOT 을 입고 순으로 뺌). 줄일 때 heldProductionIds 가
     * 있으면 그 생산 건의 예약을 먼저 WAITING 으로 되돌려 available 을 채운다. 부품은 입고·예약과 같은 잠금으로 읽고, 응답 전에 저장해 다른 처리가 먼저
     * 바꿨으면 PART_CONFLICT.
     */
    @Transactional
    public AdjustStockResponse adjust(
            Long partId,
            AdjustStockRequest request,
            String adjustedBy,
            List<Long> heldProductionIds) {
        Part part = findPartForUpdate(partId);
        List<Long> reclaimed = reclaim(part, heldProductionIds);
        // qty 는 ±AdjustStockRequest.MAX_QTY 안이라 더해도 넘치지 않는다 (부호를 뒤집지 않고 비교)
        if (part.getAvailable() + request.qty() < 0) {
            throw new CustomException(InventoryErrorCode.INSUFFICIENT_AVAILABLE);
        }
        StockAdjustment adjustment =
                stockAdjustmentRepository.save(
                        StockAdjustment.record(
                                partId, request.qty(), request.reason(), adjustedBy));
        stockHistoryRepository.save(StockHistory.adjust(adjustment));
        String lotNo = null;
        if (adjustment.isIncrease()) {
            lotNo = increase(part, adjustment);
        } else {
            decrease(part, -adjustment.getQty());
        }
        flushOrConflict(partId);
        return AdjustStockResponse.of(adjustment, part, lotNo, reclaimed);
    }

    /**
     * 부품 대기로 되돌린 생산 건을 다시 READY 로 돌린다: 거둔 뒤 저장이 실패해 예약이 RESERVED 그대로일 때 inventory.reserved 를 다시 보낸다
     * (production 은 WAITING_MATERIAL 에서 받아 READY 로 바꾼다).
     */
    @Transactional
    public void restoreHeld(List<Long> heldProductionIds) {
        log.warn("재고 조정 저장 실패로 거둔 생산 건을 되돌림: productionIds={}", heldProductionIds);
        heldProductionIds.forEach(id -> eventPublisher.publish(new InventoryReserved(id)));
    }

    /**
     * 거둔 생산 건의 이 부품 예약을 RESERVED → WAITING 으로 되돌리고 재고를 푼다 (RELEASE 이력). 되돌린 생산 건 id 를 돌려준다. 이미
     * RESERVED 가 아닌 건은 inventory.reserved 로 READY 로 돌려보낸다.
     */
    private List<Long> reclaim(Part part, List<Long> heldProductionIds) {
        if (heldProductionIds.isEmpty()) {
            return List.of();
        }
        List<PartReservation> lines = findReservedLinesOf(part.getId(), heldProductionIds);
        for (PartReservation line : lines) {
            part.release(line.getQty());
            line.waitAgain();
            stockHistoryRepository.save(
                    StockHistory.releaseForProduction(
                            part.getId(), line.getQty(), line.getProductionId()));
        }
        Set<Long> reclaimed =
                lines.stream().map(PartReservation::getProductionId).collect(Collectors.toSet());
        // 그 사이 예약이 바뀌어 거두지 못한 생산 건은 부품 대기로 남지 않게 READY 로 되돌린다
        heldProductionIds.stream()
                .filter(id -> !reclaimed.contains(id))
                .forEach(id -> eventPublisher.publish(new InventoryReserved(id)));
        return heldProductionIds.stream().filter(reclaimed::contains).toList();
    }

    /** 늘린 수량으로 조정 LOT 을 만들고, 입고와 같은 규칙으로 기다리는 줄을 채운다. 조정 LOT 번호를 돌려준다. */
    private String increase(Part part, StockAdjustment adjustment) {
        part.receive(adjustment.getQty());
        PartLot lot =
                partLotRepository.save(
                        PartLot.adjustIn(
                                part.getId(),
                                adjustment.getId(),
                                adjustment.getQty(),
                                LocalDateTime.now()));
        partReservationService.fillWaitingLinesAfterRestock(part);
        return lot.getLotNo();
    }

    /** 줄인 수량을 LOT 에서 입고 순(입고 시각, 같으면 id)으로 뺀다. LOT 이 모자라면 실물과 LOT 이 어긋난 데이터라 PART_LOT_SHORTAGE. */
    private void decrease(Part part, int qty) {
        part.takeOut(qty);
        int left = qty;
        for (PartLot lot : findOpenLots(part.getId())) {
            left -= lot.take(left);
            if (left == 0) {
                return;
            }
        }
        log.error("PART_LOT_SHORTAGE partId={} missing={} (재고 조정)", part.getId(), left);
        throw new CustomException(InventoryErrorCode.PART_LOT_SHORTAGE);
    }

    private void flushOrConflict(Long partId) {
        try {
            partRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.info("다른 작업이 먼저 바꿔 재고 조정 거절: partId={}", partId);
            throw new CustomException(InventoryErrorCode.PART_CONFLICT);
        }
    }

    /** 입고·예약과 같은 잠금(OPTIMISTIC_FORCE_INCREMENT)으로 읽는다. 없거나 지운 부품이면 PART_NOT_FOUND. */
    private Part findPartForUpdate(Long partId) {
        return partRepository.findAllByIdInAndIsDeletedFalse(Set.of(partId)).stream()
                .findFirst()
                .orElseThrow(() -> new CustomException(InventoryErrorCode.PART_NOT_FOUND));
    }

    /** 거둔 생산 건 중 아직 이 부품을 잡고 있는(RESERVED) 예약. 그 사이 취소·완료된 건은 빠진다. */
    private List<PartReservation> findReservedLinesOf(Long partId, List<Long> productionIds) {
        return reservationRepository.findAllByPartIdAndProductionIdInAndStatus(
                partId, productionIds, ReservationStatus.RESERVED);
    }

    private List<PartLot> findOpenLots(Long partId) {
        return partLotRepository
                .findAllByPartIdInAndRemainingQtyGreaterThanOrderByReceivedAtAscIdAsc(
                        List.of(partId), 0);
    }
}

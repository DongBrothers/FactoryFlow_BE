package com.factoryflow.inventory.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartLot;
import com.factoryflow.inventory.domain.StockAdjustment;
import com.factoryflow.inventory.domain.StockHistory;
import com.factoryflow.inventory.dto.request.AdjustStockRequest;
import com.factoryflow.inventory.dto.response.AdjustStockResponse;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.PartLotRepository;
import com.factoryflow.inventory.repository.PartRepository;
import com.factoryflow.inventory.repository.StockAdjustmentRepository;
import com.factoryflow.inventory.repository.StockHistoryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 재고 조정 (docs/specs/stock-adjustment.md). */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAdjustmentService {

    private final PartRepository partRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final PartLotRepository partLotRepository;
    private final PartReservationService partReservationService;

    /**
     * 바로 쓸 수 있는 수량을 qty 만큼 늘리거나(조정 LOT, 기다리는 줄 채움) 줄인다(LOT 을 입고 순으로 뺌). 부품은 입고·예약과 같은 잠금으로 읽고, 응답
     * 전에 저장해 다른 처리가 먼저 바꿨으면 PART_CONFLICT.
     */
    @Transactional
    public AdjustStockResponse adjust(Long partId, AdjustStockRequest request, String adjustedBy) {
        Part part = findPartForUpdate(partId);
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
        return AdjustStockResponse.of(adjustment, part, lotNo);
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

    private List<PartLot> findOpenLots(Long partId) {
        return partLotRepository
                .findAllByPartIdInAndRemainingQtyGreaterThanOrderByReceivedAtAscIdAsc(
                        List.of(partId), 0);
    }
}

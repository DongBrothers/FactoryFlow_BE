package com.factoryflow.inventory.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.client.production.ProductionClient;
import com.factoryflow.inventory.client.production.dto.request.HoldMaterialCandidateRequest;
import com.factoryflow.inventory.client.production.dto.request.HoldMaterialRequest;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartReservation;
import com.factoryflow.inventory.domain.ReservationStatus;
import com.factoryflow.inventory.dto.request.AdjustStockRequest;
import com.factoryflow.inventory.dto.response.AdjustStockResponse;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.PartRepository;
import com.factoryflow.inventory.repository.PartReservationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 재고 조정 (docs/specs/stock-adjustment.md). 저장은 StockAdjustmentWriter 가 한다. */
@Service
@RequiredArgsConstructor
public class StockAdjustmentService {

    private final PartRepository partRepository;
    private final PartReservationRepository reservationRepository;
    private final ProductionClient productionClient;
    private final StockAdjustmentWriter stockAdjustmentWriter;

    /**
     * 줄일 수량이 바로 쓸 수 있는 수량보다 많으면, 모자란 만큼 찜한 부품을 거둘 발행 전 차를 production 에 먼저 정해 받는다 (트랜잭션 밖). 그 뒤 저장이
     * 실패하면 거둔 차를 READY 로 되돌린다 ("찜한 부품 거둬들이기").
     */
    public AdjustStockResponse adjust(Long partId, AdjustStockRequest request, String adjustedBy) {
        List<Long> held = holdIfShort(partId, request.qty());
        try {
            return stockAdjustmentWriter.adjust(partId, request, adjustedBy, held);
        } catch (RuntimeException e) {
            if (!held.isEmpty()) {
                stockAdjustmentWriter.restoreHeld(held);
            }
            throw e;
        }
    }

    /** 줄일 수량이 available 보다 많을 때만 production 에 묻는다. 잡아 둔 예약이 없으면 묻지 않고 INSUFFICIENT_AVAILABLE. */
    private List<Long> holdIfShort(Long partId, int qty) {
        if (qty > 0) {
            return List.of();
        }
        int shortfall = -qty - findPart(partId).getAvailable();
        if (shortfall <= 0) {
            return List.of();
        }
        List<HoldMaterialCandidateRequest> candidates =
                findReservedLatestFirst(partId).stream()
                        .map(
                                line ->
                                        new HoldMaterialCandidateRequest(
                                                line.getProductionId(), line.getQty()))
                        .toList();
        if (candidates.isEmpty()) {
            throw new CustomException(InventoryErrorCode.INSUFFICIENT_AVAILABLE);
        }
        return askProductionToHold(new HoldMaterialRequest(partId, shortfall, candidates));
    }

    /**
     * production 이 거둔 뒤 응답만 실패했을 수 있으므로, 호출이 실패하면(PRODUCTION_UNAVAILABLE) 부품을 다 잡은 후보를 READY 로 되돌린다
     * (PR #172 리뷰).
     */
    private List<Long> askProductionToHold(HoldMaterialRequest request) {
        try {
            return productionClient.holdForMaterial(request);
        } catch (CustomException e) {
            if (e.getErrorCode() == InventoryErrorCode.PRODUCTION_UNAVAILABLE) {
                stockAdjustmentWriter.restoreHeld(
                        request.candidates().stream()
                                .map(HoldMaterialCandidateRequest::productionId)
                                .toList());
            }
            throw e;
        }
    }

    private Part findPart(Long partId) {
        return partRepository
                .findByIdAndIsDeletedFalse(partId)
                .orElseThrow(() -> new CustomException(InventoryErrorCode.PART_NOT_FOUND));
    }

    /** 이 부품을 잡아 둔 예약, 늦게 잡은 것부터. */
    private List<PartReservation> findReservedLatestFirst(Long partId) {
        return reservationRepository.findAllByPartIdAndStatusOrderByIdDesc(
                partId, ReservationStatus.RESERVED);
    }
}

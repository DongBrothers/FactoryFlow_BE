package com.factoryflow.inventory.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartLot;
import com.factoryflow.inventory.dto.response.LotStockResponse;
import com.factoryflow.inventory.dto.response.PartLotStockResponse;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.PartLotRepository;
import com.factoryflow.inventory.repository.PartRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부품 LOT 재고 조회 (docs/specs/part-lot.md). 차에 어느 LOT 이 들어갔는지(계보)는 mes 가 갖는다
 * (docs/specs/part-genealogy.md).
 */
@Service
@RequiredArgsConstructor
public class PartLotService {

    private final PartLotRepository partLotRepository;
    private final PartRepository partRepository;

    /**
     * 부품의 남은 LOT 을 다음에 쓸 순서(입고 순)로, 창고 실물과 함께. 부품이 없으면 PART_NOT_FOUND (docs/specs/list-apis.md "5.
     * LOT 별 재고").
     */
    @Transactional(readOnly = true)
    public PartLotStockResponse findLotsOfPart(Long partId) {
        Part part = findActivePartOrThrow(partId);
        List<LotStockResponse> lots =
                findOpenLots(partId).stream().map(LotStockResponse::from).toList();
        return PartLotStockResponse.of(part, lots);
    }

    private Part findActivePartOrThrow(Long partId) {
        return partRepository
                .findByIdAndIsDeletedFalse(partId)
                .orElseThrow(() -> new CustomException(InventoryErrorCode.PART_NOT_FOUND));
    }

    private List<PartLot> findOpenLots(Long partId) {
        return partLotRepository
                .findAllByPartIdInAndRemainingQtyGreaterThanOrderByReceivedAtAscIdAsc(
                        List.of(partId), 0);
    }
}

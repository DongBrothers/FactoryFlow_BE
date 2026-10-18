package com.factoryflow.inventory.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.dto.request.CreatePartRequest;
import com.factoryflow.inventory.dto.request.UpdatePartRequest;
import com.factoryflow.inventory.dto.response.InternalPartResponse;
import com.factoryflow.inventory.dto.response.PartShortageResponse;
import com.factoryflow.inventory.dto.response.PartStockResponse;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.PartRepository;
import com.factoryflow.inventory.repository.PartReservationRepository;
import com.factoryflow.inventory.repository.WaitingTotal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부품 재고 조회 (docs/specs/list-apis.md "3. 부품 재고 목록", docs/specs/purchase-manual.md)와 부품 등록·수정
 * (docs/specs/master-data.md "1. 부품").
 */
@Service
@RequiredArgsConstructor
public class PartService {

    private final PartRepository partRepository;
    private final PartReservationRepository reservationRepository;

    /** 삭제되지 않은 부품의 재고, 부품 코드 순. */
    @Transactional(readOnly = true)
    public List<PartStockResponse> findParts() {
        return activeParts().stream().map(PartStockResponse::from).toList();
    }

    /**
     * 부족한 부품: 기다리는(WAITING) 수량 합이 바로 쓸 수 있는 수량보다 많은 부품. 부족 수량 큰 순, 같으면 코드 순. 구매 담당이 보고 직접 구매한다
     * (docs/specs/purchase-manual.md "1. 부족 부품 조회").
     */
    @Transactional(readOnly = true)
    public List<PartShortageResponse> findShortages() {
        Map<Long, WaitingTotal> waitingByPartId =
                sumWaitingByPart().stream()
                        .collect(Collectors.toMap(WaitingTotal::partId, Function.identity()));
        return findActiveParts(waitingByPartId.keySet()).stream()
                .map(part -> shortageOf(part, waitingByPartId.get(part.getId())))
                .filter(shortage -> shortage.shortQty() > 0)
                .sorted(
                        Comparator.comparingInt(PartShortageResponse::shortQty)
                                .reversed()
                                .thenComparing(PartShortageResponse::code))
                .toList();
    }

    /** 새 부품 (재고 0, LOT 없음). code 가 지운 부품을 포함해 이미 있으면 PART_CODE_DUPLICATED. */
    @Transactional
    public PartStockResponse register(CreatePartRequest request) {
        if (isCodeTaken(request.code())) {
            throw new CustomException(InventoryErrorCode.PART_CODE_DUPLICATED);
        }
        try {
            Part part =
                    partRepository.saveAndFlush(
                            Part.register(request.code(), request.name(), request.unit()));
            return PartStockResponse.from(part);
        } catch (DataIntegrityViolationException e) {
            // 같은 code 를 동시에 등록하면 UNIQUE 제약이 늦은 쪽을 막는다
            throw new CustomException(InventoryErrorCode.PART_CODE_DUPLICATED);
        }
    }

    /** 이름과 단위만 바꾼다. 없거나 지운 부품이면 PART_NOT_FOUND. */
    @Transactional
    public PartStockResponse update(Long partId, UpdatePartRequest request) {
        Part part = findActivePart(partId);
        part.changeDetails(request.name(), request.unit());
        return PartStockResponse.from(part);
    }

    /** 내부 API: 있는 부품만 (삭제된 부품은 빠진다). purchase 가 구매 건의 부품을 확인하고 스냅샷을 남긴다. */
    @Transactional(readOnly = true)
    public List<InternalPartResponse> findPartsByIds(List<Long> ids) {
        return findActiveParts(ids).stream().map(InternalPartResponse::from).toList();
    }

    private static PartShortageResponse shortageOf(Part part, WaitingTotal waiting) {
        return PartShortageResponse.of(
                part, waiting.waitingQty().intValue(), waiting.waitingProductions().intValue());
    }

    private List<Part> activeParts() {
        return partRepository.findAllByIsDeletedFalseOrderByCodeAsc();
    }

    private List<Part> findActiveParts(Collection<Long> ids) {
        return partRepository.findAllByIdInAndIsDeletedFalseOrderByCodeAsc(ids);
    }

    private boolean isCodeTaken(String code) {
        return partRepository.existsByCode(code);
    }

    private Part findActivePart(Long partId) {
        return partRepository
                .findByIdAndIsDeletedFalse(partId)
                .orElseThrow(() -> new CustomException(InventoryErrorCode.PART_NOT_FOUND));
    }

    private List<WaitingTotal> sumWaitingByPart() {
        return reservationRepository.sumWaitingByPart();
    }
}

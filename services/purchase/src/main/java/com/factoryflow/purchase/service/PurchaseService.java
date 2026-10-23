package com.factoryflow.purchase.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.purchase.client.inventory.PartClient;
import com.factoryflow.purchase.client.inventory.dto.response.PartResponse;
import com.factoryflow.purchase.client.inventory.dto.response.PartShortageResponse;
import com.factoryflow.purchase.domain.Purchase;
import com.factoryflow.purchase.domain.PurchaseStatus;
import com.factoryflow.purchase.dto.request.CreatePurchaseRequest;
import com.factoryflow.purchase.dto.response.PurchaseResponse;
import com.factoryflow.purchase.dto.response.PurchaseSuggestionResponse;
import com.factoryflow.purchase.event.publisher.payload.PurchaseReceived;
import com.factoryflow.purchase.exception.PurchaseErrorCode;
import com.factoryflow.purchase.repository.PurchaseRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구매 건: 구매 담당의 발주, 입고 (docs/specs/purchase-manual.md, docs/specs/purchase-receiving.md). */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseRepository purchaseRepository;
    private final PurchaseSuggestionReader purchaseSuggestionReader;
    private final PurchaseWriter purchaseWriter;
    private final PartClient partClient;
    private final EventPublisher eventPublisher;

    /**
     * 구매 담당의 구매 건 만들기 = 발주 (docs/specs/purchase-manual.md "2"). 부품은 트랜잭션 밖에서 inventory 에 확인해 코드·이름을
     * 스냅샷으로 받고, 저장은 PurchaseWriter 가 한다. 없는 부품이면 PART_NOT_FOUND, inventory 가 응답하지 않으면
     * INVENTORY_UNAVAILABLE.
     */
    public PurchaseResponse create(CreatePurchaseRequest request, String orderedBy) {
        PartResponse part = findPartOrThrow(request.partId());
        return purchaseWriter.save(part, request, orderedBy);
    }

    private PartResponse findPartOrThrow(Long partId) {
        return partClient.findParts(List.of(partId)).stream()
                .filter(part -> part.id().equals(partId))
                .findFirst()
                .orElseThrow(() -> new CustomException(PurchaseErrorCode.PART_NOT_FOUND));
    }

    /** 상태별 구매 목록, id 순서로 lastId 다음부터 size 개. 다음 페이지가 있는지 알려고 하나 더 읽는다. */
    @Transactional(readOnly = true)
    public CursorPage<PurchaseResponse> findPurchases(String status, Long lastId, int size) {
        List<PurchaseResponse> rows =
                findPurchasesAfter(PurchaseStatus.valueOf(status), lastId, size + 1).stream()
                        .map(PurchaseResponse::from)
                        .toList();
        return CursorPage.of(rows, size, PurchaseResponse::purchaseId);
    }

    /**
     * 입고: ORDERED → RECEIVED + purchase.received (inventory 가 재고를 늘리고 기다리던 예약 줄을 채운다). 수량 전체를 한 번에
     * 입고한다.
     */
    @Transactional
    public PurchaseResponse receive(Long purchaseId) {
        Purchase purchase = findPurchase(purchaseId);
        if (!purchase.isOrdered()) {
            throw invalidStatus(purchase);
        }
        purchase.receive(LocalDateTime.now());
        flushOrConflict(purchaseId);
        eventPublisher.publish(
                new PurchaseReceived(purchase.getId(), purchase.getPartId(), purchase.getQty()));
        return PurchaseResponse.from(purchase);
    }

    private static CustomException invalidStatus(Purchase purchase) {
        log.info(
                "구매 상태가 맞지 않아 거절: purchaseId={} status={}", purchase.getId(), purchase.getStatus());
        return new CustomException(PurchaseErrorCode.INVALID_PURCHASE_STATUS);
    }

    /** 응답·이벤트 전에 저장해 version 을 확인한다. 다른 처리가 먼저 바꿨으면 PURCHASE_CONFLICT. */
    private void flushOrConflict(Long purchaseId) {
        try {
            purchaseRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.info("다른 작업이 먼저 바꿔 거절: purchaseId={}", purchaseId);
            throw new CustomException(PurchaseErrorCode.PURCHASE_CONFLICT);
        }
    }

    private List<Purchase> findPurchasesAfter(PurchaseStatus status, Long lastId, int limit) {
        return purchaseRepository.findAllByStatusAndIdGreaterThanOrderByIdAsc(
                status, lastId, Limit.of(limit));
    }

    private Purchase findPurchase(Long purchaseId) {
        return purchaseRepository
                .findById(purchaseId)
                .orElseThrow(() -> new CustomException(PurchaseErrorCode.PURCHASE_NOT_FOUND));
    }

    /**
     * 발주 제안: inventory 의 부족 부품마다 발주 중(ORDERED) 수량을 빼 더 살 수량을 정하고, 가장 최근 구매 건의 공급업체를 붙인다
     * (docs/specs/purchase-suggestion.md). inventory 호출은 트랜잭션 밖이고, DB 읽기는 PurchaseSuggestionReader
     * 가 한 트랜잭션에서 한다.
     */
    public List<PurchaseSuggestionResponse> findSuggestions() {
        List<PartShortageResponse> shortages = partClient.findShortages();
        if (shortages.isEmpty()) {
            return List.of();
        }
        PurchaseHistory history =
                purchaseSuggestionReader.read(
                        shortages.stream().map(PartShortageResponse::partId).toList());
        return shortages.stream()
                .map(
                        shortage ->
                                PurchaseSuggestionResponse.of(
                                        shortage,
                                        history.orderedQtyOf(shortage.partId()),
                                        history.lastSupplierOf(shortage.partId())))
                .sorted(
                        Comparator.comparingLong(PurchaseSuggestionResponse::suggestedQty)
                                .reversed()
                                .thenComparing(PurchaseSuggestionResponse::partCode))
                .toList();
    }
}

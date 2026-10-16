package com.factoryflow.purchase.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.purchase.domain.Purchase;
import com.factoryflow.purchase.domain.PurchaseSource;
import com.factoryflow.purchase.domain.PurchaseStatus;
import com.factoryflow.purchase.domain.Supplier;
import com.factoryflow.purchase.dto.response.PurchaseResponse;
import com.factoryflow.purchase.event.listener.payload.ShortageDetected;
import com.factoryflow.purchase.event.publisher.payload.PurchaseReceived;
import com.factoryflow.purchase.exception.PurchaseErrorCode;
import com.factoryflow.purchase.repository.PurchaseRepository;
import com.factoryflow.purchase.repository.SupplierRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구매 건: 부족 자동 요청, 발주, 입고 (docs/specs/purchase-receiving.md). */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseRepository purchaseRepository;
    private final SupplierRepository supplierRepository;
    private final EventPublisher eventPublisher;

    /**
     * inventory.shortage: 같은 부품의 AUTO + REQUESTED 구매 건이 있으면 수량을 더하고, 없으면 새로 만든다. 이미 발주한 건은 공급업체에 보낸
     * 수량이 바뀌면 안 되므로 건드리지 않는다. 같은 구매 건을 발주와 동시에 바꾸면 version 충돌로 실패하고 재시도 때 새 건을 만든다.
     */
    @Transactional
    public void requestForShortage(ShortageDetected event) {
        Optional<Purchase> requested = findRequestedAuto(event.partId());
        if (requested.isPresent()) {
            requested.get().addQty(event.shortQty());
            return;
        }
        purchaseRepository.save(
                Purchase.requestForShortage(
                        event.partId(), event.partCode(), event.partName(), event.shortQty()));
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

    /** 발주: REQUESTED → ORDERED. 공급업체가 있어야 한다. */
    @Transactional
    public PurchaseResponse order(Long purchaseId, Long supplierId) {
        Purchase purchase = findPurchase(purchaseId);
        if (!purchase.isRequested()) {
            throw invalidStatus(purchase);
        }
        Supplier supplier = findSupplier(supplierId);
        purchase.order(supplier.getId(), LocalDateTime.now());
        flushOrConflict(purchaseId);
        return PurchaseResponse.from(purchase);
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

    private Optional<Purchase> findRequestedAuto(Long partId) {
        return purchaseRepository.findFirstByPartIdAndSourceAndStatusOrderByIdAsc(
                partId, PurchaseSource.AUTO, PurchaseStatus.REQUESTED);
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

    private Supplier findSupplier(Long supplierId) {
        return supplierRepository
                .findByIdAndIsDeletedFalse(supplierId)
                .orElseThrow(() -> new CustomException(PurchaseErrorCode.SUPPLIER_NOT_FOUND));
    }
}

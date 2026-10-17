package com.factoryflow.purchase.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.purchase.client.inventory.dto.response.PartResponse;
import com.factoryflow.purchase.domain.Purchase;
import com.factoryflow.purchase.domain.Supplier;
import com.factoryflow.purchase.dto.request.CreatePurchaseRequest;
import com.factoryflow.purchase.dto.response.PurchaseResponse;
import com.factoryflow.purchase.exception.PurchaseErrorCode;
import com.factoryflow.purchase.repository.PurchaseRepository;
import com.factoryflow.purchase.repository.SupplierRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 구매 건 저장. inventory 확인(트랜잭션 밖, PurchaseService)과 나눠 DB 커넥션을 오래 잡지 않는다. */
@Component
@RequiredArgsConstructor
public class PurchaseWriter {

    private final PurchaseRepository purchaseRepository;
    private final SupplierRepository supplierRepository;

    /** 공급업체를 확인하고(없으면 SUPPLIER_NOT_FOUND) 발주 상태의 구매 건을 저장한다. 이벤트는 보내지 않는다. */
    @Transactional
    public PurchaseResponse save(
            PartResponse part, CreatePurchaseRequest request, String orderedBy) {
        Supplier supplier = findSupplier(request.supplierId());
        Purchase purchase = purchaseRepository.save(toPurchase(part, request, supplier, orderedBy));
        return PurchaseResponse.from(purchase);
    }

    private static Purchase toPurchase(
            PartResponse part, CreatePurchaseRequest request, Supplier supplier, String orderedBy) {
        return Purchase.order(
                part.id(),
                part.code(),
                part.name(),
                request.qty(),
                supplier.getId(),
                orderedBy,
                LocalDateTime.now());
    }

    private Supplier findSupplier(Long supplierId) {
        return supplierRepository
                .findByIdAndIsDeletedFalse(supplierId)
                .orElseThrow(() -> new CustomException(PurchaseErrorCode.SUPPLIER_NOT_FOUND));
    }
}

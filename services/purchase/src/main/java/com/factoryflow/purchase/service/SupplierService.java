package com.factoryflow.purchase.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.purchase.domain.Supplier;
import com.factoryflow.purchase.dto.request.CreateSupplierRequest;
import com.factoryflow.purchase.dto.request.UpdateSupplierRequest;
import com.factoryflow.purchase.dto.response.SupplierResponse;
import com.factoryflow.purchase.exception.PurchaseErrorCode;
import com.factoryflow.purchase.repository.SupplierRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공급업체 목록과 등록·수정 (docs/specs/master-data.md "2. 공급업체"). 삭제는 하지 않는다. */
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;

    /** 발주 때 고를 공급업체 목록. 몇 곳뿐이라 한 번에 준다. */
    @Transactional(readOnly = true)
    public List<SupplierResponse> findSuppliers() {
        return findAll().stream().map(SupplierResponse::from).toList();
    }

    @Transactional
    public SupplierResponse register(CreateSupplierRequest request) {
        Supplier supplier =
                supplierRepository.save(Supplier.register(request.name(), request.contact()));
        return SupplierResponse.from(supplier);
    }

    /** 없거나 지운 공급업체면 SUPPLIER_NOT_FOUND. */
    @Transactional
    public SupplierResponse update(Long supplierId, UpdateSupplierRequest request) {
        Supplier supplier = findActiveSupplier(supplierId);
        supplier.change(request.name(), request.contact());
        return SupplierResponse.from(supplier);
    }

    private Supplier findActiveSupplier(Long supplierId) {
        return supplierRepository
                .findByIdAndIsDeletedFalse(supplierId)
                .orElseThrow(() -> new CustomException(PurchaseErrorCode.SUPPLIER_NOT_FOUND));
    }

    private List<Supplier> findAll() {
        return supplierRepository.findAllByIsDeletedFalseOrderByIdAsc();
    }
}

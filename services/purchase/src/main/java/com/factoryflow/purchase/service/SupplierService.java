package com.factoryflow.purchase.service;

import com.factoryflow.purchase.domain.Supplier;
import com.factoryflow.purchase.dto.response.SupplierResponse;
import com.factoryflow.purchase.repository.SupplierRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공급업체. 기본 데이터만 있고 등록·수정은 이후 기능이다. */
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;

    /** 발주 때 고를 공급업체 목록. 몇 곳뿐이라 한 번에 준다. */
    @Transactional(readOnly = true)
    public List<SupplierResponse> findSuppliers() {
        return findAll().stream().map(SupplierResponse::from).toList();
    }

    private List<Supplier> findAll() {
        return supplierRepository.findAllByIsDeletedFalseOrderByIdAsc();
    }
}

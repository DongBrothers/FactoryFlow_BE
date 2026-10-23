package com.factoryflow.purchase.service;

import com.factoryflow.purchase.domain.Supplier;
import java.util.Map;

/** 부품별 발주 중(ORDERED) 수량 합과 가장 최근 구매 건의 공급업체. 한 읽기 트랜잭션에서 읽은 같은 시점 값이다. */
public record PurchaseHistory(
        Map<Long, Long> orderedQtyByPart, Map<Long, Supplier> lastSupplierByPart) {

    public long orderedQtyOf(Long partId) {
        return orderedQtyByPart.getOrDefault(partId, 0L);
    }

    public Supplier lastSupplierOf(Long partId) {
        return lastSupplierByPart.get(partId);
    }
}

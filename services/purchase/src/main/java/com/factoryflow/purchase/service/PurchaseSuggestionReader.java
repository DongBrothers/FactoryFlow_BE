package com.factoryflow.purchase.service;

import com.factoryflow.purchase.domain.Purchase;
import com.factoryflow.purchase.domain.Supplier;
import com.factoryflow.purchase.repository.OrderedTotal;
import com.factoryflow.purchase.repository.PurchaseRepository;
import com.factoryflow.purchase.repository.SupplierRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발주 제안의 DB 읽기. 발주 중 합계와 최근 공급업체를 한 읽기 트랜잭션에서 읽어 같은 시점 값으로 맞춘다. inventory 호출과 응답 만들기는
 * PurchaseService 가 트랜잭션 밖에서 한다 (docs/specs/purchase-suggestion.md).
 */
@Component
@RequiredArgsConstructor
public class PurchaseSuggestionReader {

    private final PurchaseRepository purchaseRepository;
    private final SupplierRepository supplierRepository;

    /** 부품별 발주 중 수량 합과 최근 공급업체를 한 읽기 트랜잭션에서 읽는다. */
    @Transactional(readOnly = true)
    public PurchaseHistory read(List<Long> partIds) {
        return new PurchaseHistory(orderedQtyByPart(partIds), lastSupplierByPart(partIds));
    }

    private Map<Long, Long> orderedQtyByPart(List<Long> partIds) {
        return purchaseRepository.sumOrderedQtyByPart(partIds).stream()
                .collect(Collectors.toMap(OrderedTotal::partId, OrderedTotal::qty));
    }

    /** 부품마다 가장 최근 구매 건의 공급업체. 지운 공급업체도 이름을 보여 준다. */
    private Map<Long, Supplier> lastSupplierByPart(List<Long> partIds) {
        List<Purchase> latest = findLatestPurchases(partIds);
        Map<Long, Supplier> suppliers =
                findSuppliers(
                        latest.stream()
                                .map(Purchase::getSupplierId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet()));
        Map<Long, Supplier> byPart = new HashMap<>();
        latest.stream()
                .filter(p -> suppliers.containsKey(p.getSupplierId()))
                .forEach(p -> byPart.put(p.getPartId(), suppliers.get(p.getSupplierId())));
        return byPart;
    }

    private List<Purchase> findLatestPurchases(List<Long> partIds) {
        return purchaseRepository.findAllById(purchaseRepository.findLatestIdsByPart(partIds));
    }

    private Map<Long, Supplier> findSuppliers(Set<Long> supplierIds) {
        return supplierRepository.findAllById(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));
    }
}

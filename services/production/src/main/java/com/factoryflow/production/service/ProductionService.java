package com.factoryflow.production.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.production.domain.Bom;
import com.factoryflow.production.domain.Product;
import com.factoryflow.production.domain.Production;
import com.factoryflow.production.event.listener.OrderCreatedProduct;
import com.factoryflow.production.event.publisher.MaterialRequested;
import com.factoryflow.production.event.publisher.MaterialRequestedPart;
import com.factoryflow.production.repository.BomRepository;
import com.factoryflow.production.repository.ProductRepository;
import com.factoryflow.production.repository.ProductionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionService {

    private final ProductionRepository productionRepository;
    private final ProductRepository productRepository;
    private final BomRepository bomRepository;
    private final EventPublisher eventPublisher;

    /**
     * 주문 속 제품마다 생산 건(PLANNED)을 만들고 필요한 부품을 production.material-requested 로 요청한다. 같은 제품이 여러 줄이면 수량을
     * 합친다. 같은 주문·제품의 생산 건이 이미 있거나, 제품이나 BOM 이 주문 이후 사라졌으면 그 제품은 만들지 않는다. 생산 건, 제품, BOM 은 제품 수와 상관없이
     * 한 번씩 조회한다.
     */
    @Transactional
    public void planAll(Long orderId, List<OrderCreatedProduct> products) {
        Map<Long, Integer> qtyByProductId = mergeByProductId(products);
        Set<Long> productIds = qtyByProductId.keySet();
        Set<Long> planned = plannedProductIds(orderId, productIds);
        Set<Long> existing = existingProductIds(productIds);
        Map<Long, List<Bom>> bomsByProductId = bomsByProductId(productIds);
        qtyByProductId.forEach(
                (productId, qty) -> {
                    List<Bom> boms = bomsByProductId.getOrDefault(productId, List.of());
                    if (shouldPlan(orderId, productId, planned, existing, boms)) {
                        plan(orderId, productId, qty, boms);
                    }
                });
    }

    private static Map<Long, Integer> mergeByProductId(List<OrderCreatedProduct> products) {
        return products.stream()
                .collect(
                        Collectors.toMap(
                                OrderCreatedProduct::productId,
                                OrderCreatedProduct::qty,
                                Integer::sum,
                                LinkedHashMap::new));
    }

    private Set<Long> plannedProductIds(Long orderId, Set<Long> productIds) {
        return productionRepository.findAllByOrderIdAndProductIdIn(orderId, productIds).stream()
                .map(Production::getProductId)
                .collect(Collectors.toSet());
    }

    private Set<Long> existingProductIds(Set<Long> productIds) {
        return productRepository.findAllByIdInAndIsDeletedFalse(productIds).stream()
                .map(Product::getId)
                .collect(Collectors.toSet());
    }

    private Map<Long, List<Bom>> bomsByProductId(Set<Long> productIds) {
        return bomRepository.findAllByProductIdInAndIsDeletedFalse(productIds).stream()
                .collect(Collectors.groupingBy(Bom::getProductId));
    }

    /** 이미 생산 건이 있거나, 제품이나 BOM 이 주문 이후 사라졌으면 false. 건너뛰는 이유를 로그로 남긴다. */
    private static boolean shouldPlan(
            Long orderId, Long productId, Set<Long> planned, Set<Long> existing, List<Bom> boms) {
        if (planned.contains(productId)) {
            log.info("생산 건이 이미 있어 건너뜀: orderId={} productId={}", orderId, productId);
            return false;
        }
        if (!existing.contains(productId)) {
            log.error(
                    "SAGA_SKIPPED orderId={} productId={} reason=PRODUCT_NOT_FOUND",
                    orderId,
                    productId);
            return false;
        }
        if (boms.isEmpty()) {
            log.error(
                    "SAGA_SKIPPED orderId={} productId={} reason=BOM_NOT_FOUND",
                    orderId,
                    productId);
            return false;
        }
        return true;
    }

    private void plan(Long orderId, Long productId, int qty, List<Bom> boms) {
        Production production = productionRepository.save(Production.plan(orderId, productId, qty));
        eventPublisher.publish(
                new MaterialRequested(
                        production.getId(),
                        boms.stream()
                                .map(
                                        b ->
                                                new MaterialRequestedPart(
                                                        b.getPartId(), b.getQtyPerUnit() * qty))
                                .toList()));
    }
}

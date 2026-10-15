package com.factoryflow.production.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.domain.Bom;
import com.factoryflow.production.domain.Product;
import com.factoryflow.production.domain.Production;
import com.factoryflow.production.event.listener.payload.OrderCreatedProduct;
import com.factoryflow.production.event.publisher.payload.MaterialRequested;
import com.factoryflow.production.event.publisher.payload.MaterialRequestedPart;
import com.factoryflow.production.event.publisher.payload.ProductionCancelled;
import com.factoryflow.production.event.publisher.payload.ProductionStatusChanged;
import com.factoryflow.production.exception.ProductionErrorCode;
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
     * 주문 속 제품마다 수량만큼 생산 건(차량 1대씩, PLANNED)을 만들고, 차량마다 필요한 부품을 production.material-requested 로 요청하고
     * production.status-changed(PLANNED) 로 order 에 알린다. 같은 제품이 여러 줄이면 수량을 합친다. 같은 주문·제품의 생산 건이 이미
     * 있거나, 제품이나 BOM 이 주문 이후 사라졌으면 그 제품은 만들지 않는다. 생산 건, 제품, BOM 은 제품 수와 상관없이 한 번씩 조회한다
     * (docs/specs/production-order-created.md).
     */
    @Transactional
    public void planAll(Long orderId, List<OrderCreatedProduct> products) {
        Map<Long, Integer> qtyByProductId = mergeByProductId(products);
        requirePositiveQty(qtyByProductId);
        Set<Long> productIds = qtyByProductId.keySet();
        Set<Long> planned = plannedProductIds(orderId, productIds);
        Set<Long> existing = existingProductIds(productIds);
        Map<Long, List<Bom>> bomsByProductId = bomsByProductId(productIds);
        qtyByProductId.forEach(
                (productId, qty) -> {
                    List<Bom> boms = bomsByProductId.getOrDefault(productId, List.of());
                    if (shouldPlan(orderId, productId, planned, existing, boms)) {
                        planVehicles(orderId, productId, qty, boms);
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

    /** 수량이 1 이상이 아니면 처리 오류다. order 가 1 이상만 받으므로 정상 흐름에서는 생기지 않는다. */
    private static void requirePositiveQty(Map<Long, Integer> qtyByProductId) {
        if (qtyByProductId.values().stream().anyMatch(qty -> qty < 1)) {
            throw new CustomException(ProductionErrorCode.INVALID_ORDER_QTY);
        }
    }

    /** 차량 1대마다 생산 건을 만들고, 차량 1대분 부품을 요청하고, order 에 생산 건을 알린다. */
    private void planVehicles(Long orderId, Long productId, int qty, List<Bom> boms) {
        List<MaterialRequestedPart> partsPerVehicle =
                boms.stream()
                        .map(b -> new MaterialRequestedPart(b.getPartId(), b.getQtyPerUnit()))
                        .toList();
        for (int seq = 1; seq <= qty; seq++) {
            Production production =
                    productionRepository.save(Production.plan(orderId, productId, seq));
            publishMaterialRequested(production, partsPerVehicle);
            publishStatusChanged(production);
        }
    }

    /**
     * inventory.reserved: PLANNED, WAITING_MATERIAL 생산 건을 READY 로 바꾼다. 취소되었거나 실패한 생산 건이면 예약을 풀도록
     * production.cancelled 를 다시 보낸다. 그 밖의 상태에서 온 결과는 무시한다
     * (docs/specs/production-reservation-result.md).
     */
    @Transactional
    public void markReserved(Long productionId) {
        Production production = findProduction(productionId);
        if (production.isClosed()) {
            log.info(
                    "끝난 생산 건에 예약이 늦게 와 취소를 다시 보냄: productionId={} status={}",
                    productionId,
                    production.getStatus());
            publishCancelled(production);
            return;
        }
        if (!production.markReady()) {
            log.info(
                    "허용되지 않은 상태라 inventory.reserved 무시: productionId={} status={}",
                    productionId,
                    production.getStatus());
            return;
        }
        publishStatusChanged(production);
    }

    /**
     * inventory.reservation-pending: PLANNED 생산 건을 WAITING_MATERIAL 로 바꾼다. 그 밖의 상태에서 온 결과는 무시한다.
     */
    @Transactional
    public void markWaitingForMaterial(Long productionId) {
        Production production = findProduction(productionId);
        if (!production.waitForMaterial()) {
            log.info(
                    "허용되지 않은 상태라 inventory.reservation-pending 무시: productionId={} status={}",
                    productionId,
                    production.getStatus());
            return;
        }
        publishStatusChanged(production);
    }

    private Production findProduction(Long productionId) {
        return productionRepository
                .findById(productionId)
                .orElseThrow(() -> new CustomException(ProductionErrorCode.PRODUCTION_NOT_FOUND));
    }

    /** 차량 1대분 부품 예약을 inventory 에 요청한다. 예약이 끝나면(inventory.reserved) 생산이 시작된다. */
    private void publishMaterialRequested(
            Production production, List<MaterialRequestedPart> parts) {
        eventPublisher.publish(new MaterialRequested(production.getId(), parts));
    }

    /** 생산 건의 예약을 풀도록 inventory 에 알린다. */
    private void publishCancelled(Production production) {
        eventPublisher.publish(
                new ProductionCancelled(production.getOrderId(), production.getId()));
    }

    /** 생산 건 상태를 order 에 알린다. order 는 이것으로 차량 상태를 바꾼다. */
    private void publishStatusChanged(Production production) {
        eventPublisher.publish(
                new ProductionStatusChanged(
                        production.getOrderId(),
                        production.getId(),
                        production.getProductId(),
                        production.getStatus()));
    }
}

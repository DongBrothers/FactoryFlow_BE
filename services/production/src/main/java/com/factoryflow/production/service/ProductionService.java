package com.factoryflow.production.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.production.domain.Bom;
import com.factoryflow.production.domain.Product;
import com.factoryflow.production.domain.Production;
import com.factoryflow.production.domain.ProductionStatus;
import com.factoryflow.production.dto.request.InternalHoldMaterialCandidateRequest;
import com.factoryflow.production.dto.request.InternalHoldMaterialRequest;
import com.factoryflow.production.dto.response.AwaitingReleaseResponse;
import com.factoryflow.production.dto.response.InternalMaterialHoldResponse;
import com.factoryflow.production.dto.response.ReleaseWorkOrderResponse;
import com.factoryflow.production.event.listener.payload.OrderCreatedProduct;
import com.factoryflow.production.event.publisher.payload.MaterialRequested;
import com.factoryflow.production.event.publisher.payload.MaterialRequestedPart;
import com.factoryflow.production.event.publisher.payload.ProductionCancelRejected;
import com.factoryflow.production.event.publisher.payload.ProductionCancelled;
import com.factoryflow.production.event.publisher.payload.ProductionCompleted;
import com.factoryflow.production.event.publisher.payload.ProductionReleased;
import com.factoryflow.production.event.publisher.payload.ProductionStatusChanged;
import com.factoryflow.production.event.publisher.payload.ReleaseCancelRequested;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.production.repository.BomRepository;
import com.factoryflow.production.repository.ProductRepository;
import com.factoryflow.production.repository.ProductionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
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
     * 있으면 건너뛰고, 제품이나 BOM 이 주문 이후 사라졌으면 그 제품은 취소된 생산 건으로 만든다. 생산 건, 제품, BOM 은 제품 수와 상관없이 한 번씩 조회한다
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
                    String rejectReason = rejectReason(productId, existing, boms);
                    if (planned.contains(productId)) {
                        log.info("생산 건이 이미 있어 건너뜀: orderId={} productId={}", orderId, productId);
                    } else if (rejectReason != null) {
                        rejectVehicles(orderId, productId, qty, rejectReason);
                    } else {
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

    /** 제품이나 BOM 이 주문 이후 사라졌으면 그 이유, 만들 수 있으면 null. */
    private static String rejectReason(Long productId, Set<Long> existing, List<Bom> boms) {
        if (!existing.contains(productId)) {
            return "PRODUCT_NOT_FOUND";
        }
        return boms.isEmpty() ? "BOM_NOT_FOUND" : null;
    }

    /**
     * 만들 수 없는 제품: 수량만큼 생산 건을 바로 CANCELLED 로 만들고 production.status-changed(CANCELLED) 로 order 에 알린다.
     * 부품은 요청하지 않는다 (docs/specs/stalled-flows.md S8).
     */
    private void rejectVehicles(Long orderId, Long productId, int qty, String reason) {
        log.error("SAGA_REJECTED orderId={} productId={} reason={}", orderId, productId, reason);
        for (int seq = 1; seq <= qty; seq++) {
            Production production =
                    productionRepository.save(Production.reject(orderId, productId, seq));
            publishStatusChanged(production);
        }
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
     * inventory.reserved: PLANNED, WAITING_MATERIAL 생산 건을 READY(작업지시 발행 대기)로 바꾼다. 작업지시는 생산 담당이 발행한다
     * (docs/specs/work-order-release.md). 취소된 생산 건이면 예약을 풀도록 production.cancelled 를 다시 보낸다. 그 밖의
     * 상태에서 온 결과는 무시한다 (docs/specs/production-reservation-result.md).
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
     * 생산 담당이 작업지시를 발행한다: READY 이고 아직 발행하지 않은 생산 건에 발행 시각을 남기고 production.released 로 mes 에 넘긴다.
     * 작업지시는 mes 가 만든다. 생산 건은 잠그고 version 을 올려 읽어, 같은 차의 취소 수신과 겹치면 취소 쪽이 재시도해 발행된 차로 처리한다
     * (docs/specs/mes-split.md).
     */
    @Transactional
    public ReleaseWorkOrderResponse release(Long productionId) {
        Production production = findProductionForRelease(productionId);
        if (production.getStatus() != ProductionStatus.READY) {
            log.info(
                    "READY 가 아니라 작업지시를 발행할 수 없음: productionId={} status={}",
                    productionId,
                    production.getStatus());
            throw new CustomException(ProductionErrorCode.PRODUCTION_NOT_READY);
        }
        if (production.isReleased()) {
            throw new CustomException(ProductionErrorCode.WORK_ORDER_ALREADY_RELEASED);
        }
        production.release(LocalDateTime.now());
        eventPublisher.publish(
                new ProductionReleased(
                        production.getId(), production.getOrderId(), production.getProductId()));
        return ReleaseWorkOrderResponse.from(production);
    }

    /**
     * 재고 조정이 찜한 부품을 거둘 때: 후보를 순서대로 보며 발행 전 READY 생산 건을 WAITING_MATERIAL 로 되돌리고, 거둔 qty 합이 neededQty
     * 이상이 되면 멈춘다. 모자라면 아무것도 바꾸지 않고 NOT_ENOUGH_UNRELEASED. 생산 건은 발행과 같은 잠금으로 읽어 동시에 발행되는 차는 한쪽만 이긴다
     * (docs/specs/stock-adjustment.md "찜한 부품 거둬들이기").
     */
    @Transactional
    public InternalMaterialHoldResponse holdForMaterial(InternalHoldMaterialRequest request) {
        List<Production> held = new ArrayList<>();
        int heldQty = 0;
        for (InternalHoldMaterialCandidateRequest candidate : request.candidates()) {
            Optional<Production> production = findProductionToHold(candidate.productionId());
            if (production.isPresent() && production.get().holdForMaterial()) {
                held.add(production.get());
                heldQty += candidate.qty();
            }
            if (heldQty >= request.neededQty()) {
                held.forEach(this::publishStatusChanged);
                return InternalMaterialHoldResponse.from(held);
            }
        }
        log.info(
                "발행 전 차에서 거둬도 모자라 거절: partId={} neededQty={} heldQty={}",
                request.partId(),
                request.neededQty(),
                heldQty);
        throw new CustomException(ProductionErrorCode.NOT_ENOUGH_UNRELEASED);
    }

    /** 작업지시 발행 대기 목록: READY 이고 아직 발행하지 않은 생산 건, 먼저 생긴 순 (docs/specs/work-order-release.md). */
    @Transactional(readOnly = true)
    public CursorPage<AwaitingReleaseResponse> findAwaitingRelease(Long lastId, int size) {
        List<Production> rows = findAwaitingReleaseAfter(lastId, size + 1);
        Map<Long, Product> products =
                findProductsByIds(rows.stream().map(Production::getProductId).distinct().toList());
        List<AwaitingReleaseResponse> items =
                rows.stream()
                        .map(p -> AwaitingReleaseResponse.of(p, products.get(p.getProductId())))
                        .toList();
        return CursorPage.of(items, size, AwaitingReleaseResponse::productionId);
    }

    /** 잠그고 version 을 올려 읽는다 (PESSIMISTIC_FORCE_INCREMENT). 없으면 PRODUCTION_NOT_FOUND. */
    private Production findProductionForRelease(Long productionId) {
        return productionRepository
                .findForReleaseById(productionId)
                .orElseThrow(() -> new CustomException(ProductionErrorCode.PRODUCTION_NOT_FOUND));
    }

    /** 발행과 같은 잠금으로 읽는다. 없는 생산 건은 거둘 후보에서 빠진다. */
    private Optional<Production> findProductionToHold(Long productionId) {
        return productionRepository.findForReleaseById(productionId);
    }

    private List<Production> findAwaitingReleaseAfter(Long lastId, int limit) {
        return productionRepository.findAwaitingRelease(lastId, limit);
    }

    private Map<Long, Product> findProductsByIds(List<Long> productIds) {
        return productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    /**
     * mes.production-started: mes 가 첫 공정(프레스)을 시작했다. 생산 건 READY → IN_PROGRESS, order 에 알린다. 이후에는
     * 취소할 수 없다. 이미 시작·완료됐으면(다시 온 알림, 완성이 먼저 온 경우) 그대로 둔다 (docs/specs/mes-split.md).
     */
    @Transactional
    public void startProduction(Long productionId) {
        Production production = findProduction(productionId);
        if (!production.start()) {
            if (production.isClosed()) {
                log.warn("취소된 생산 건에 생산 시작이 와 무시: productionId={}", productionId);
            } else {
                log.info(
                        "READY 가 아니라 생산 시작 알림 무시: productionId={} status={}",
                        productionId,
                        production.getStatus());
            }
            return;
        }
        flushOrConflict(productionId);
        publishStatusChanged(production);
    }

    /**
     * mes.vehicle-completed: mes 가 검사 양품으로 차를 완성했다. 생산 건 IN_PROGRESS(시작 알림보다 먼저 왔으면 READY) →
     * COMPLETED 후 production.completed (inventory 는 부품 사용 처리와 완성차 재고, order 는 차량 COMPLETED). 이미
     * 완료됐거나 다른 상태면 그대로 둔다 (docs/specs/production-completion.md, docs/specs/mes-split.md).
     */
    @Transactional
    public void completeProduction(Long productionId, String vin) {
        Production production = findProduction(productionId);
        if (!production.complete()) {
            log.info(
                    "IN_PROGRESS·READY 가 아니라 완성 알림 무시: productionId={} status={}",
                    productionId,
                    production.getStatus());
            return;
        }
        flushOrConflict(productionId);
        eventPublisher.publish(
                new ProductionCompleted(
                        production.getOrderId(),
                        production.getId(),
                        production.getProductId(),
                        vin));
    }

    /**
     * order.cancel-requested: 주문의 생산 건마다 판단한다. 이미 취소됐으면 production.cancelled 를 다시, 작업지시 발행 전이면 취소 +
     * production.cancelled, 발행했으면 시작 여부를 아는 mes 에 production.release-cancel-requested 로 묻고, 시작했으면
     * production.cancel-rejected(ALREADY_STARTED, 지금 상태). 생산 건이 없으면 PRODUCTION_NOT_FOUND (order 가
     * 차량을 다 알 때만 요청하므로 정상 흐름에서는 없음, docs/specs/order-cancel.md, docs/specs/mes-split.md).
     */
    @Transactional
    public void cancelAll(Long orderId) {
        List<Production> productions = findProductionsOf(orderId);
        if (productions.isEmpty()) {
            throw new CustomException(ProductionErrorCode.PRODUCTION_NOT_FOUND);
        }
        productions.forEach(this::cancelOne);
    }

    private void cancelOne(Production production) {
        if (production.isClosed()) {
            publishCancelled(production);
        } else if (production.getStatus() == ProductionStatus.READY && production.isReleased()) {
            eventPublisher.publish(
                    new ReleaseCancelRequested(production.getOrderId(), production.getId()));
        } else if (production.cancel()) {
            publishCancelled(production);
        } else {
            publishCancelRejected(production, production.getStatus());
        }
    }

    /**
     * mes.work-order-cancelled: 발행한 차를 mes 가 첫 공정 전에 취소했다. 생산 건 CANCELLED + production.cancelled
     * (예약 해제, order 차량 CANCELLED). 이미 취소됐으면 cancelled 를 다시 보낸다. 다른 상태면 그대로 둔다.
     */
    @Transactional
    public void cancelReleased(Long productionId) {
        Production production = findProduction(productionId);
        if (production.isClosed() || production.cancel()) {
            publishCancelled(production);
            return;
        }
        log.warn(
                "취소할 수 없는 상태라 작업지시 취소 알림 무시: productionId={} status={}",
                productionId,
                production.getStatus());
    }

    /**
     * mes.work-order-cancel-rejected: 발행한 차의 첫 공정을 mes 가 이미 시작해 취소를 거절했다.
     * production.cancel-rejected 를 작업지시 상태(IN_PROGRESS, COMPLETED)로 보낸다. 생산 건은 시작 알림이 아직 오지 않아
     * READY 일 수 있어 mes 가 알려 준 상태를 쓴다.
     */
    @Transactional
    public void rejectReleasedCancel(Long productionId, ProductionStatus startedStatus) {
        publishCancelRejected(findProduction(productionId), startedStatus);
    }

    private List<Production> findProductionsOf(Long orderId) {
        return productionRepository.findAllByOrderIdOrderByIdAsc(orderId);
    }

    /**
     * 이벤트를 발행하기 전에 저장해 version 을 확인한다. 다른 트랜잭션이 먼저 바꿨으면 PRODUCTION_CONFLICT. (발행의 Outbox INSERT 가
     * 먼저 flush 를 일으키면 충돌이 번역되지 않은 예외로 나가므로 순서를 지킨다)
     */
    private void flushOrConflict(Long productionId) {
        try {
            productionRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.info("다른 작업이 먼저 바꿔 거절: productionId={}", productionId);
            throw new CustomException(ProductionErrorCode.PRODUCTION_CONFLICT);
        }
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

    /**
     * inventory.reservation-failed: 부품이 없어 예약할 수 없는 PLANNED 생산 건을 CANCELLED 로 끝내고
     * production.cancelled 로 알린다. 이미 취소됐거나 다른 상태면 그대로 둔다 (docs/specs/stalled-flows.md S18).
     */
    @Transactional
    public void cancelForMissingParts(Long productionId, List<Long> partIds) {
        Production production = findProduction(productionId);
        if (production.getStatus() != ProductionStatus.PLANNED || !production.cancel()) {
            log.info(
                    "PLANNED 가 아니라 inventory.reservation-failed 무시: productionId={} status={}",
                    productionId,
                    production.getStatus());
            return;
        }
        log.error(
                "SAGA_REJECTED productionId={} reason=PART_NOT_FOUND partIds={}",
                productionId,
                partIds);
        publishCancelled(production);
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

    /** 취소를 거절했다고 order 에 알린다. status 는 order 가 취소 요청 중이던 차량을 되돌릴 상태다. */
    private void publishCancelRejected(Production production, ProductionStatus status) {
        eventPublisher.publish(
                new ProductionCancelRejected(
                        production.getOrderId(),
                        production.getId(),
                        ProductionCancelRejected.ALREADY_STARTED,
                        status));
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

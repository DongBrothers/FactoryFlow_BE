package com.factoryflow.production.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.domain.Bom;
import com.factoryflow.production.domain.Product;
import com.factoryflow.production.domain.Production;
import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.domain.ProductionStatus;
import com.factoryflow.production.domain.WorkOrder;
import com.factoryflow.production.domain.WorkOrderStep;
import com.factoryflow.production.event.listener.payload.OrderCreatedProduct;
import com.factoryflow.production.event.publisher.payload.MaterialRequested;
import com.factoryflow.production.event.publisher.payload.MaterialRequestedPart;
import com.factoryflow.production.event.publisher.payload.ProductionCancelRejected;
import com.factoryflow.production.event.publisher.payload.ProductionCancelled;
import com.factoryflow.production.event.publisher.payload.ProductionCompleted;
import com.factoryflow.production.event.publisher.payload.ProductionStatusChanged;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.production.repository.BomRepository;
import com.factoryflow.production.repository.ProductRepository;
import com.factoryflow.production.repository.ProductionRepository;
import com.factoryflow.production.repository.WorkOrderRepository;
import com.factoryflow.production.repository.WorkOrderStepRepository;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderStepRepository workOrderStepRepository;
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
     * inventory.reserved: PLANNED, WAITING_MATERIAL 생산 건을 READY 로 바꾸고 작업지시 1건과 공정 5개를 발행한다 (생산의
     * 트리거, docs/specs/production-work-order.md). 취소된 생산 건이면 예약을 풀도록 production.cancelled 를 다시 보낸다.
     * 그 밖의 상태에서 온 결과는 무시한다 (docs/specs/production-reservation-result.md).
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
        releaseWorkOrder(production);
        publishStatusChanged(production);
    }

    /** 작업지시 1건과 라우팅 순서대로 공정 5개(PENDING)를 만든다. 생산 건당 하나뿐이다. */
    private void releaseWorkOrder(Production production) {
        if (workOrderRepository.existsByProductionId(production.getId())) {
            log.info("작업지시가 이미 있어 건너뜀: productionId={}", production.getId());
            return;
        }
        WorkOrder workOrder =
                workOrderRepository.save(WorkOrder.release(production, LocalDateTime.now()));
        workOrderStepRepository.saveAll(
                Arrays.stream(ProductionProcess.values())
                        .map(process -> WorkOrderStep.plan(workOrder.getId(), process))
                        .toList());
    }

    /**
     * 첫 공정(프레스)을 시작했다: 생산 건 READY → IN_PROGRESS, order 에 알린다. 이후에는 취소할 수 없다. 작업자의 공정 시작
     * (WorkOrderService)과 같은 트랜잭션에서 부른다.
     */
    @Transactional
    public void startProduction(Long productionId) {
        Production production = findProduction(productionId);
        if (!production.start()) {
            log.info(
                    "READY 가 아니라 생산을 시작할 수 없음: productionId={} status={}",
                    productionId,
                    production.getStatus());
            throw new CustomException(ProductionErrorCode.WORK_ORDER_CLOSED);
        }
        flushOrConflict(productionId);
        publishStatusChanged(production);
    }

    /**
     * 검사 양품으로 차가 완성됐다: 생산 건 IN_PROGRESS → COMPLETED 후 production.completed (inventory 는 부품 사용 처리와
     * 완성차 재고, order 는 차량 COMPLETED). IN_PROGRESS 가 아니면 WORK_ORDER_CLOSED. 공정 종료(WorkOrderService)와
     * 같은 트랜잭션에서 부른다 (docs/specs/production-completion.md).
     */
    @Transactional
    public void completeProduction(Long productionId, String vin) {
        Production production = findProduction(productionId);
        if (!production.complete()) {
            log.info(
                    "IN_PROGRESS 가 아니라 생산을 완료할 수 없음: productionId={} status={}",
                    productionId,
                    production.getStatus());
            throw new CustomException(ProductionErrorCode.WORK_ORDER_CLOSED);
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
     * order.cancel-requested: 주문의 생산 건마다 프레스 배정 전이면 취소(작업지시도) + production.cancelled, 배정 후면
     * production.cancel-rejected(ALREADY_STARTED, 지금 상태). 이미 취소된 건은 cancelled 를 다시 보낸다. 생산 건이 없으면
     * PRODUCTION_NOT_FOUND (order 가 차량을 다 알 때만 요청하므로 정상 흐름에서는 없음, docs/specs/order-cancel.md).
     */
    @Transactional
    public void cancelAll(Long orderId) {
        List<Production> productions = findProductionsOf(orderId);
        if (productions.isEmpty()) {
            throw new CustomException(ProductionErrorCode.PRODUCTION_NOT_FOUND);
        }
        Map<Long, WorkOrder> workOrdersByProductionId =
                findWorkOrdersOf(productions.stream().map(Production::getId).toList());
        for (Production production : productions) {
            if (production.isClosed() || production.cancel()) {
                WorkOrder workOrder = workOrdersByProductionId.get(production.getId());
                if (workOrder != null) {
                    workOrder.cancel();
                }
                publishCancelled(production);
            } else {
                eventPublisher.publish(
                        new ProductionCancelRejected(
                                orderId,
                                production.getId(),
                                ProductionCancelRejected.ALREADY_STARTED,
                                production.getStatus()));
            }
        }
    }

    private List<Production> findProductionsOf(Long orderId) {
        return productionRepository.findAllByOrderIdOrderByIdAsc(orderId);
    }

    /** 생산 건들의 작업지시를 한 번에 읽는다 (작업지시가 아직 없는 생산 건은 빠진다). */
    private Map<Long, WorkOrder> findWorkOrdersOf(List<Long> productionIds) {
        return workOrderRepository.findAllByProductionIdIn(productionIds).stream()
                .collect(Collectors.toMap(WorkOrder::getProductionId, Function.identity()));
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

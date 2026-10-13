package com.factoryflow.production.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.production.domain.Bom;
import com.factoryflow.production.domain.Production;
import com.factoryflow.production.event.publisher.MaterialRequested;
import com.factoryflow.production.event.publisher.MaterialRequestedPart;
import com.factoryflow.production.repository.BomRepository;
import com.factoryflow.production.repository.ProductRepository;
import com.factoryflow.production.repository.ProductionRepository;
import java.util.List;
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
     * 주문 속 제품 1종의 생산 건(PLANNED)을 만들고 필요한 부품을 production.material-requested 로 요청한다. 같은 주문·제품의 생산 건이
     * 이미 있거나, 제품이나 BOM 이 주문 이후 사라졌으면 만들지 않는다.
     */
    @Transactional
    public void plan(Long orderId, Long productId, int qty) {
        if (productionRepository.existsByOrderIdAndProductId(orderId, productId)) {
            log.info("생산 건이 이미 있어 건너뜀: orderId={} productId={}", orderId, productId);
            return;
        }
        if (!productRepository.existsByIdAndIsDeletedFalse(productId)) {
            log.error(
                    "SAGA_SKIPPED orderId={} productId={} reason=PRODUCT_NOT_FOUND",
                    orderId,
                    productId);
            return;
        }
        List<Bom> boms = bomRepository.findAllByProductIdAndIsDeletedFalse(productId);
        if (boms.isEmpty()) {
            log.error(
                    "SAGA_SKIPPED orderId={} productId={} reason=BOM_NOT_FOUND",
                    orderId,
                    productId);
            return;
        }

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

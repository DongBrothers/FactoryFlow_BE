package com.factoryflow.production.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.domain.Bom;
import com.factoryflow.production.domain.Product;
import com.factoryflow.production.dto.request.BomItemRequest;
import com.factoryflow.production.dto.request.CreateProductRequest;
import com.factoryflow.production.dto.request.UpdateProductRequest;
import com.factoryflow.production.dto.response.ProductDetailResponse;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.production.repository.BomRepository;
import com.factoryflow.production.repository.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제품과 BOM 을 한 트랜잭션에 저장한다. inventory 에 부품을 확인하는 호출은 ProductService 가 트랜잭션 밖에서 끝낸 뒤 부른다
 * (docs/specs/master-data.md "3. 제품 + BOM").
 */
@Component
@RequiredArgsConstructor
public class ProductWriter {

    private final ProductRepository productRepository;
    private final BomRepository bomRepository;

    @Transactional
    public ProductDetailResponse register(CreateProductRequest request) {
        Product product;
        try {
            product =
                    productRepository.saveAndFlush(
                            Product.register(request.code(), request.name()));
        } catch (DataIntegrityViolationException e) {
            // 같은 code 를 동시에 등록하면 UNIQUE 제약이 늦은 쪽을 막는다
            throw new CustomException(ProductionErrorCode.PRODUCT_CODE_DUPLICATED);
        }
        List<Bom> bom =
                bomRepository.saveAll(
                        request.bom().stream()
                                .map(item -> Bom.of(product.getId(), item.partId(), item.qty()))
                                .toList());
        return ProductDetailResponse.of(product, bom);
    }

    /**
     * 이름을 바꾸고 BOM 을 요청 목록으로 통째로 바꾼다: 남거나 다시 넣은 부품은 수량을 바꾸고(지웠던 줄은 되살림, UNIQUE(product_id, part_id)
     * 라 새 줄을 만들 수 없다), 처음 보는 부품은 새 줄, 빠진 부품은 소프트 삭제.
     */
    @Transactional
    public ProductDetailResponse update(Long productId, UpdateProductRequest request) {
        Product product = findActiveProduct(productId);
        product.rename(request.name());
        return ProductDetailResponse.of(product, replaceBom(productId, request.bom()));
    }

    /** 바뀐 뒤의 BOM 줄 전부(지운 줄 포함)를 돌려준다. */
    private List<Bom> replaceBom(Long productId, List<BomItemRequest> items) {
        Map<Long, Bom> current =
                findAllBomLines(productId).stream()
                        .collect(Collectors.toMap(Bom::getPartId, Function.identity()));
        Map<Long, Integer> wanted =
                items.stream()
                        .collect(Collectors.toMap(BomItemRequest::partId, BomItemRequest::qty));
        List<Bom> added = new ArrayList<>();
        wanted.forEach(
                (partId, qty) -> {
                    Bom line = current.get(partId);
                    if (line == null) {
                        added.add(Bom.of(productId, partId, qty));
                    } else {
                        line.keep(qty);
                    }
                });
        current.values().stream()
                .filter(line -> !wanted.containsKey(line.getPartId()))
                .forEach(Bom::delete);
        List<Bom> bom = new ArrayList<>(current.values());
        bom.addAll(bomRepository.saveAll(added));
        return bom;
    }

    private Product findActiveProduct(Long productId) {
        return productRepository
                .findByIdAndIsDeletedFalse(productId)
                .orElseThrow(() -> new CustomException(ProductionErrorCode.PRODUCT_NOT_FOUND));
    }

    private List<Bom> findAllBomLines(Long productId) {
        return bomRepository.findAllByProductId(productId);
    }
}

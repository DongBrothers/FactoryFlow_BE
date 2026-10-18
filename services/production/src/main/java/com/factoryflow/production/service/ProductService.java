package com.factoryflow.production.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.production.client.inventory.PartClient;
import com.factoryflow.production.client.inventory.dto.response.PartResponse;
import com.factoryflow.production.domain.Bom;
import com.factoryflow.production.domain.Product;
import com.factoryflow.production.dto.request.BomItemRequest;
import com.factoryflow.production.dto.request.CreateProductRequest;
import com.factoryflow.production.dto.request.UpdateProductRequest;
import com.factoryflow.production.dto.response.InternalProductResponse;
import com.factoryflow.production.dto.response.ProductDetailResponse;
import com.factoryflow.production.dto.response.ProductResponse;
import com.factoryflow.production.exception.ProductionErrorCode;
import com.factoryflow.production.repository.BomRepository;
import com.factoryflow.production.repository.ProductRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final BomRepository bomRepository;
    private final PartClient partClient;
    private final ProductWriter productWriter;

    /**
     * 제품 + BOM 등록 (docs/specs/master-data.md "3"). code 가 이미 있으면 inventory 를 부르지 않고
     * PRODUCT_CODE_DUPLICATED. 부품 확인은 트랜잭션 밖에서, 저장은 ProductWriter 가 한 트랜잭션으로 한다.
     */
    public ProductDetailResponse register(CreateProductRequest request) {
        if (isCodeTaken(request.code())) {
            throw new CustomException(ProductionErrorCode.PRODUCT_CODE_DUPLICATED);
        }
        requirePartsExist(request.bom());
        return productWriter.register(request);
    }

    /** 이름과 BOM 수정. 없거나 지운 제품이면 inventory 를 부르지 않고 PRODUCT_NOT_FOUND. */
    public ProductDetailResponse update(Long productId, UpdateProductRequest request) {
        if (!isActiveProduct(productId)) {
            throw new CustomException(ProductionErrorCode.PRODUCT_NOT_FOUND);
        }
        requirePartsExist(request.bom());
        return productWriter.update(productId, request);
    }

    /** 제품 상세: 삭제되지 않은 BOM, 부품 id 순. */
    @Transactional(readOnly = true)
    public ProductDetailResponse findProduct(Long productId) {
        Product product =
                findActiveProduct(productId)
                        .orElseThrow(
                                () -> new CustomException(ProductionErrorCode.PRODUCT_NOT_FOUND));
        return ProductDetailResponse.of(product, findActiveBom(productId));
    }

    /** 있는 제품만 돌려준다. 없는 id 와 삭제된 제품은 결과에서 빠진다. */
    @Transactional(readOnly = true)
    public List<InternalProductResponse> findProducts(List<Long> ids) {
        Set<Long> withBom = productIdsHavingBom(ids);
        return existingProducts(ids).stream()
                .map(
                        product ->
                                InternalProductResponse.of(
                                        product, withBom.contains(product.getId())))
                .toList();
    }

    /** 주문할 때 고를 수 있는 제품: 삭제되지 않았고 BOM 이 있는 제품, 코드 순 (docs/specs/list-apis.md). */
    @Transactional(readOnly = true)
    public List<ProductResponse> findOrderableProducts() {
        List<Product> products = activeProducts();
        Set<Long> withBom = productIdsHavingBom(products.stream().map(Product::getId).toList());
        return products.stream()
                .filter(product -> withBom.contains(product.getId()))
                .map(ProductResponse::from)
                .toList();
    }

    /** BOM 의 부품이 inventory 에 모두 있어야 한다 (없거나 지운 부품은 응답에서 빠진다). 장애는 client 가 503 으로 바꾼다. */
    private void requirePartsExist(List<BomItemRequest> bom) {
        List<Long> partIds = bom.stream().map(BomItemRequest::partId).toList();
        Set<Long> found =
                partClient.findParts(partIds).stream()
                        .map(PartResponse::id)
                        .collect(Collectors.toSet());
        if (!found.containsAll(partIds)) {
            throw new CustomException(ProductionErrorCode.PART_NOT_FOUND);
        }
    }

    private boolean isCodeTaken(String code) {
        return productRepository.existsByCode(code);
    }

    private boolean isActiveProduct(Long productId) {
        return findActiveProduct(productId).isPresent();
    }

    private Optional<Product> findActiveProduct(Long productId) {
        return productRepository.findByIdAndIsDeletedFalse(productId);
    }

    private List<Bom> findActiveBom(Long productId) {
        return bomRepository.findAllByProductIdAndIsDeletedFalseOrderByPartIdAsc(productId);
    }

    private List<Product> activeProducts() {
        return productRepository.findAllByIsDeletedFalseOrderByCodeAsc();
    }

    private Set<Long> productIdsHavingBom(List<Long> ids) {
        return new HashSet<>(bomRepository.findProductIdsHavingBom(ids));
    }

    private List<Product> existingProducts(List<Long> ids) {
        return productRepository.findAllByIdInAndIsDeletedFalse(ids);
    }
}

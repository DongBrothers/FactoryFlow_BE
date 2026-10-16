package com.factoryflow.production.service;

import com.factoryflow.production.domain.Product;
import com.factoryflow.production.dto.response.InternalProductResponse;
import com.factoryflow.production.dto.response.ProductResponse;
import com.factoryflow.production.repository.BomRepository;
import com.factoryflow.production.repository.ProductRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final BomRepository bomRepository;

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

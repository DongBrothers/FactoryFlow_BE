package com.factoryflow.production.service;

import com.factoryflow.production.dto.response.InternalProductResponse;
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
        Set<Long> withBom = new HashSet<>(bomRepository.findProductIdsHavingBom(ids));
        return productRepository.findAllByIdInAndIsDeletedFalse(ids).stream()
                .map(
                        product ->
                                InternalProductResponse.of(
                                        product, withBom.contains(product.getId())))
                .toList();
    }
}

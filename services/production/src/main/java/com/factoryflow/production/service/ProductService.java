package com.factoryflow.production.service;

import com.factoryflow.production.dto.InternalProductResponse;
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
public class ProductQueryService {

    private final ProductRepository productRepository;
    private final BomRepository bomRepository;

    /** 있는 제품만 돌려준다. 없는 id 는 결과에서 빠진다. */
    @Transactional(readOnly = true)
    public List<InternalProductResponse> findProducts(List<Long> ids) {
        Set<Long> withBom = new HashSet<>(bomRepository.findProductIdsHavingBom(ids));
        return productRepository.findAllByIdInAndIsDeletedFalse(ids).stream()
                .map(
                        product ->
                                new InternalProductResponse(
                                        product.getId(),
                                        product.getCode(),
                                        product.getName(),
                                        withBom.contains(product.getId())))
                .toList();
    }
}

package com.factoryflow.inventory.service;

import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.dto.response.PartStockResponse;
import com.factoryflow.inventory.repository.PartRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 부품 재고 조회 (docs/specs/list-apis.md "3. 부품 재고 목록"). */
@Service
@RequiredArgsConstructor
public class PartService {

    private final PartRepository partRepository;

    /** 삭제되지 않은 부품의 재고, 부품 코드 순. */
    @Transactional(readOnly = true)
    public List<PartStockResponse> findParts() {
        return activeParts().stream().map(PartStockResponse::from).toList();
    }

    private List<Part> activeParts() {
        return partRepository.findAllByIsDeletedFalseOrderByCodeAsc();
    }
}

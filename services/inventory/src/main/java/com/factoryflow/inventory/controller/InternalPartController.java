package com.factoryflow.inventory.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.inventory.dto.response.InternalPartResponse;
import com.factoryflow.inventory.dto.response.InternalPartShortageResponse;
import com.factoryflow.inventory.service.PartService;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서비스끼리만 부르는 부품 확인 (purchase 가 구매 건을 만들 때). gateway 에 노출하지 않는다. */
@RestController
@RequestMapping("/internal/parts")
@RequiredArgsConstructor
public class InternalPartController {

    private final PartService partService;

    @GetMapping
    public ApiResponse<List<InternalPartResponse>> findParts(
            @RequestParam("ids") @NotEmpty List<Long> ids) {
        List<InternalPartResponse> parts = partService.findPartsByIds(ids);
        return ApiResponse.ok(parts);
    }

    /** purchase 의 발주 제안이 부른다 (docs/specs/purchase-suggestion.md). */
    @GetMapping("/shortages")
    public ApiResponse<List<InternalPartShortageResponse>> findShortages() {
        List<InternalPartShortageResponse> shortages = partService.findShortagesForPurchase();
        return ApiResponse.ok(shortages);
    }
}

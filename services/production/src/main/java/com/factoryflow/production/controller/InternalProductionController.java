package com.factoryflow.production.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.production.dto.request.InternalHoldMaterialRequest;
import com.factoryflow.production.dto.response.InternalMaterialHoldResponse;
import com.factoryflow.production.service.ProductionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/productions")
@RequiredArgsConstructor
public class InternalProductionController {

    private final ProductionService productionService;

    /** 재고 조정이 찜한 부품을 거둘 때 발행 전 차를 부품 대기로 되돌린다 (docs/specs/stock-adjustment.md). */
    @PostMapping("/material-holds")
    public ApiResponse<InternalMaterialHoldResponse> holdMaterial(
            @Valid @RequestBody InternalHoldMaterialRequest request) {
        InternalMaterialHoldResponse held = productionService.holdForMaterial(request);
        return ApiResponse.ok(held);
    }
}

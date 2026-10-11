package com.factoryflow.production.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.production.dto.InternalProductResponse;
import com.factoryflow.production.service.ProductQueryService;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 간 호출 전용. gateway 에 노출하지 않는다. */
@RestController
@RequestMapping("/internal/products")
@RequiredArgsConstructor
public class InternalProductController {

    private final ProductQueryService productQueryService;

    @GetMapping
    public ApiResponse<List<InternalProductResponse>> findProducts(
            @RequestParam("ids") @NotEmpty List<Long> ids) {
        return ApiResponse.ok(productQueryService.findProducts(ids));
    }
}

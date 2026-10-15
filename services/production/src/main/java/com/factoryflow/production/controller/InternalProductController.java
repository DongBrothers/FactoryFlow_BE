package com.factoryflow.production.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.production.dto.response.InternalProductResponse;
import com.factoryflow.production.service.ProductService;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 제품 조회. 서비스 간 호출 전용(order 의 주문 생성)이라 gateway 에 노출하지 않는다. */
@RestController
@RequestMapping("/internal/products")
@RequiredArgsConstructor
public class InternalProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<List<InternalProductResponse>> findProducts(
            @RequestParam("ids") @NotEmpty List<Long> ids) {
        List<InternalProductResponse> products = productService.findProducts(ids);
        return ApiResponse.ok(products);
    }
}

package com.factoryflow.production.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.production.dto.response.ProductResponse;
import com.factoryflow.production.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "제품", description = "주문할 수 있는 제품")
@RestController
@RequestMapping("/production/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(
            summary = "제품 목록",
            description =
                    "주문할 때 고를 수 있는 제품(삭제되지 않았고 BOM 이 있는 제품)을 제품 코드 순으로 본다. productId 를 주문 생성에 넣는다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 제품이 없으면 빈 목록")
    })
    @GetMapping
    public ApiResponse<List<ProductResponse>> findProducts() {
        List<ProductResponse> products = productService.findOrderableProducts();
        return ApiResponse.ok(products);
    }
}

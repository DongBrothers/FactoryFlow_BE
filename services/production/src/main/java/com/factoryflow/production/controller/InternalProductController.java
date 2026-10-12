package com.factoryflow.production.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.production.dto.response.InternalProductResponse;
import com.factoryflow.production.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "제품 (내부)", description = "서비스 간 호출 전용. gateway 에 노출하지 않음")
@RestController
@RequestMapping("/internal/products")
@RequiredArgsConstructor
public class InternalProductController {

    private final ProductService productService;

    @Operation(
            summary = "제품 조회 (서비스 간 내부용)",
            description =
                    "주어진 id 중 있는 제품만 돌려준다. 없는 id 와 삭제된 제품은 결과에서 빠진다. orderable 은 삭제되지 않은 BOM 이 있는지다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공 (없는 제품, 삭제된 제품은 결과에서 빠짐)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "ids 가 없거나 숫자가 아님")
    })
    @GetMapping
    public ApiResponse<List<InternalProductResponse>> findProducts(
            @Parameter(description = "조회할 제품 번호들", example = "1,2") @RequestParam("ids") @NotEmpty
                    List<Long> ids) {
        List<InternalProductResponse> products = productService.findProducts(ids);
        return ApiResponse.ok(products);
    }
}

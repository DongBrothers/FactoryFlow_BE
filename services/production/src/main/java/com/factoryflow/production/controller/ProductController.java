package com.factoryflow.production.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.production.dto.request.CreateProductRequest;
import com.factoryflow.production.dto.request.UpdateProductRequest;
import com.factoryflow.production.dto.response.ProductDetailResponse;
import com.factoryflow.production.dto.response.ProductResponse;
import com.factoryflow.production.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "제품", description = "제품과 BOM")
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

    @Operation(
            summary = "제품 등록",
            description =
                    "제품과 BOM 을 등록한다. BOM 의 부품이 inventory 에 있는지 확인한 뒤 저장하고, 등록하면 바로 주문할 수 있다. 코드는 나중에 바꿀 수 없다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "제품 등록됨. 제품 상세를 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "요청 형식 오류 (코드·이름 없음·길이 초과, BOM 비어 있음, partId 없음, 수량 1 미만, 같은 부품 두 번)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "BOM 의 부품이 inventory 에 없음 (PART_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "같은 코드의 제품이 있음, 지운 제품 포함 (PRODUCT_CODE_DUPLICATED)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "inventory 응답 없음, 3초 초과, 비정상 응답 (INVENTORY_UNAVAILABLE)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductDetailResponse> register(
            @Valid @RequestBody CreateProductRequest request) {
        ProductDetailResponse product = productService.register(request);
        return ApiResponse.created(product);
    }

    @Operation(
            summary = "제품 수정",
            description =
                    "제품 이름과 BOM 을 바꾼다. BOM 은 요청 목록으로 통째로 바뀐다. 이미 들어간 주문은 그때의 BOM 그대로이고 새 주문부터 새 BOM 을 쓴다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "제품 수정됨. 제품 상세를 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "요청 형식 오류 (이름 없음·길이 초과, BOM 비어 있음, partId 없음, 수량 1 미만, 같은 부품 두 번, productId 가 양수가 아님)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "제품 없음 (PRODUCT_NOT_FOUND) 또는 BOM 의 부품이 inventory 에 없음 (PART_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "inventory 응답 없음, 3초 초과, 비정상 응답 (INVENTORY_UNAVAILABLE)")
    })
    @PutMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> update(
            @Parameter(description = "제품 id", example = "31") @PathVariable("productId") @Positive
                    Long productId,
            @Valid @RequestBody UpdateProductRequest request) {
        ProductDetailResponse product = productService.update(productId, request);
        return ApiResponse.ok(product);
    }

    @Operation(
            summary = "제품 상세",
            description = "제품과 지금 BOM(삭제되지 않은 줄, 부품 id 순)을 본다. BOM 수정 화면에서 쓴다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "productId 가 양수가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "제품 없음 (PRODUCT_NOT_FOUND)")
    })
    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> findProduct(
            @Parameter(description = "제품 id", example = "31") @PathVariable("productId") @Positive
                    Long productId) {
        ProductDetailResponse product = productService.findProduct(productId);
        return ApiResponse.ok(product);
    }
}

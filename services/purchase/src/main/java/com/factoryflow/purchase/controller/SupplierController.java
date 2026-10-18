package com.factoryflow.purchase.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.purchase.dto.request.CreateSupplierRequest;
import com.factoryflow.purchase.dto.request.UpdateSupplierRequest;
import com.factoryflow.purchase.dto.response.SupplierResponse;
import com.factoryflow.purchase.service.SupplierService;
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

@Tag(name = "공급업체", description = "부품을 발주할 협력업체")
@RestController
@RequestMapping("/purchases/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @Operation(summary = "공급업체 목록", description = "발주 때 고를 공급업체를 모두 준다 (몇 곳뿐이라 나누지 않는다).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<List<SupplierResponse>> findSuppliers() {
        List<SupplierResponse> suppliers = supplierService.findSuppliers();
        return ApiResponse.ok(suppliers);
    }

    @Operation(summary = "공급업체 등록", description = "발주할 공급업체를 등록한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "공급업체 등록됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류 (이름 없음·100자 초과, 연락처 100자 초과)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierResponse> register(
            @Valid @RequestBody CreateSupplierRequest request) {
        SupplierResponse supplier = supplierService.register(request);
        return ApiResponse.created(supplier);
    }

    @Operation(
            summary = "공급업체 수정",
            description = "공급업체의 이름과 연락처를 바꾼다. 이미 발주한 구매 건은 같은 업체를 그대로 가리킨다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "공급업체 수정됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류 (이름 없음·100자 초과, 연락처 100자 초과, supplierId 가 양수가 아님)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "공급업체 없음 (SUPPLIER_NOT_FOUND)")
    })
    @PutMapping("/{supplierId}")
    public ApiResponse<SupplierResponse> update(
            @Parameter(description = "공급업체 번호", example = "1") @PathVariable("supplierId") @Positive
                    Long supplierId,
            @Valid @RequestBody UpdateSupplierRequest request) {
        SupplierResponse supplier = supplierService.update(supplierId, request);
        return ApiResponse.ok(supplier);
    }
}

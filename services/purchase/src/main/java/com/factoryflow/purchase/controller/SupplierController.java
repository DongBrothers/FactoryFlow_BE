package com.factoryflow.purchase.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.purchase.dto.response.SupplierResponse;
import com.factoryflow.purchase.service.SupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}

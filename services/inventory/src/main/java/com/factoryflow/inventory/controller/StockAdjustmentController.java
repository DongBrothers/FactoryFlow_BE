package com.factoryflow.inventory.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.inventory.dto.request.AdjustStockRequest;
import com.factoryflow.inventory.dto.response.AdjustStockResponse;
import com.factoryflow.inventory.service.StockAdjustmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "재고 조정", description = "창고 실물에 맞춰 부품 수량 고치기")
@RestController
@RequestMapping("/inventory/parts/{partId}/adjustments")
@RequiredArgsConstructor
public class StockAdjustmentController {

    private final StockAdjustmentService stockAdjustmentService;

    @Operation(
            summary = "재고 조정",
            description =
                    "부품의 바로 쓸 수 있는 수량을 늘리거나(qty 양수) 줄인다(음수). 늘리면 조정 LOT 을 만들고 입고처럼 기다리던 차의 줄을 채운다. 줄이면 LOT 을 입고 순으로 빼고, 생산에 잡힌 수량은 줄이지 않는다. 조정한 직원은 로그인한 직원(X-User-Id)이다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "조정함. 조정 뒤 수량을 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "qty 없음·0, 사유 없음·255자 초과, X-User-Id 없음·빈 값·36자 초과, partId 가 양수가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "부품 없음 (PART_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "줄이는 수량이 바로 쓸 수 있는 수량보다 많음 (INSUFFICIENT_AVAILABLE), 다른 작업이 먼저 바꿈 (PART_CONFLICT)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdjustStockResponse> adjust(
            @Parameter(description = "부품 id", example = "1012") @PathVariable("partId") @Positive
                    Long partId,
            @Parameter(description = "조정한 직원 (gateway 가 넣는 로그인 직원 번호)", example = "6c1e2f3a-…")
                    @RequestHeader("X-User-Id")
                    @NotBlank
                    @Size(max = 36)
                    String adjustedBy,
            @Valid @RequestBody AdjustStockRequest request) {
        AdjustStockResponse adjustment = stockAdjustmentService.adjust(partId, request, adjustedBy);
        return ApiResponse.created(adjustment);
    }
}

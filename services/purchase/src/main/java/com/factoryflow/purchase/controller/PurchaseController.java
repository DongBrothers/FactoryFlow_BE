package com.factoryflow.purchase.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.purchase.dto.request.OrderPurchaseRequest;
import com.factoryflow.purchase.dto.response.PurchaseResponse;
import com.factoryflow.purchase.service.PurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "구매", description = "부품 구매: 재고 부족 자동 요청을 발주하고 입고한다")
@RestController
@RequestMapping("/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;

    @Operation(
            summary = "구매 목록",
            description =
                    "상태별 구매 건을 id 커서(lastId, size)로 나눠 준다. 재고 부족으로 자동 요청된 건(AUTO)이 REQUESTED 로 쌓인다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "status 가 없거나 REQUESTED, ORDERED, RECEIVED 가 아님, lastId 음수, size 1~100 밖")
    })
    @GetMapping
    public ApiResponse<CursorPage<PurchaseResponse>> findPurchases(
            @Parameter(description = "구매 상태 (REQUESTED, ORDERED, RECEIVED)", example = "REQUESTED")
                    @RequestParam(value = "status", required = false)
                    @NotNull
                    @Pattern(regexp = "REQUESTED|ORDERED|RECEIVED")
                    String status,
            @Parameter(description = "이 id 다음부터 (처음이면 0)", example = "0")
                    @RequestParam(value = "lastId", defaultValue = "0")
                    @PositiveOrZero
                    Long lastId,
            @Parameter(description = "한 번에 받을 개수 (1~100)", example = "20")
                    @RequestParam(value = "size", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size) {
        CursorPage<PurchaseResponse> purchases =
                purchaseService.findPurchases(status, lastId, size);
        return ApiResponse.ok(purchases);
    }

    @Operation(
            summary = "발주",
            description =
                    "요청(REQUESTED) 구매 건을 공급업체에 발주한다(ORDERED). 발주한 뒤에는 같은 부품의 부족이 와도 이 건의 수량을 바꾸지 않고 새 건이 생긴다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "발주함. 구매 건을 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "purchaseId 가 양수가 아님, supplierId 없음 또는 1 미만"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "구매 건 없음 (PURCHASE_NOT_FOUND), 공급업체 없음 (SUPPLIER_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "요청 상태가 아님 (INVALID_PURCHASE_STATUS), 다른 작업이 먼저 바꿈 (PURCHASE_CONFLICT)")
    })
    @PostMapping("/{purchaseId}/order")
    public ApiResponse<PurchaseResponse> order(
            @Parameter(description = "구매 번호", example = "31") @PathVariable("purchaseId") @Positive
                    Long purchaseId,
            @Valid @RequestBody OrderPurchaseRequest request) {
        PurchaseResponse purchase = purchaseService.order(purchaseId, request.supplierId());
        return ApiResponse.ok(purchase);
    }

    @Operation(
            summary = "입고",
            description =
                    "발주(ORDERED)한 구매 건의 물건이 들어왔다(RECEIVED). 수량 전체를 한 번에 입고하고 purchase.received 를 보낸다. inventory 가 재고를 늘리고 기다리던 차에 먼저 온 순서대로 부품을 채운다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "입고함. 구매 건을 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "purchaseId 가 양수가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "구매 건 없음 (PURCHASE_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "발주 상태가 아님 (INVALID_PURCHASE_STATUS), 다른 작업이 먼저 바꿈 (PURCHASE_CONFLICT)")
    })
    @PostMapping("/{purchaseId}/receive")
    public ApiResponse<PurchaseResponse> receive(
            @Parameter(description = "구매 번호", example = "31") @PathVariable("purchaseId") @Positive
                    Long purchaseId) {
        PurchaseResponse purchase = purchaseService.receive(purchaseId);
        return ApiResponse.ok(purchase);
    }
}

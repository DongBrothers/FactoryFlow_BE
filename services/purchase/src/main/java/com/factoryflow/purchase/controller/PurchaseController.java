package com.factoryflow.purchase.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.purchase.dto.request.CreatePurchaseRequest;
import com.factoryflow.purchase.dto.response.PurchaseResponse;
import com.factoryflow.purchase.service.PurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "구매", description = "부품 구매: 구매 담당이 부족 부품을 보고 발주하고 입고한다")
@RestController
@RequestMapping("/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;

    @Operation(
            summary = "구매 목록",
            description =
                    "상태별 구매 건을 id 커서(lastId, size)로 나눠 준다. 발주 중(ORDERED)인 건을 부족 부품 조회와 함께 보고 더 살지 정한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "status 가 없거나 ORDERED, RECEIVED 가 아님, lastId 음수, size 1~100 밖")
    })
    @GetMapping
    public ApiResponse<CursorPage<PurchaseResponse>> findPurchases(
            @Parameter(description = "구매 상태 (ORDERED, RECEIVED)", example = "ORDERED")
                    @RequestParam(value = "status", required = false)
                    @NotNull
                    @Pattern(regexp = "ORDERED|RECEIVED")
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
            summary = "구매 건 만들기 (발주)",
            description =
                    "구매 담당이 부족 부품 조회를 보고 부품·수량·공급업체를 넣어 발주한다. 만들면 바로 발주(ORDERED) 상태이고 수량은 부족분보다 많이 살 수 있다. 부품은 inventory 에 확인해 코드·이름을 남기고, 발주한 직원은 로그인한 직원(X-User-Id)이다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "발주함. 구매 건을 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "X-User-Id 없음·빈 값·36자 초과, partId·qty·supplierId 없음 또는 1 미만"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "부품 없음 (PART_NOT_FOUND), 공급업체 없음 (SUPPLIER_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "inventory 응답 없음, 3초 초과, 본문이나 data 없는 응답 (INVENTORY_UNAVAILABLE)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PurchaseResponse> create(
            @Parameter(description = "발주한 직원 (gateway 가 넣는 로그인 직원 번호)", example = "6c1e2f3a-…")
                    @RequestHeader("X-User-Id")
                    @NotBlank
                    @Size(max = 36)
                    String orderedBy,
            @Valid @RequestBody CreatePurchaseRequest request) {
        PurchaseResponse purchase = purchaseService.create(request, orderedBy);
        return ApiResponse.created(purchase);
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

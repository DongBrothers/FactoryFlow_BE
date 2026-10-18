package com.factoryflow.inventory.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.inventory.dto.request.CreatePartRequest;
import com.factoryflow.inventory.dto.request.UpdatePartRequest;
import com.factoryflow.inventory.dto.response.PartLotStockResponse;
import com.factoryflow.inventory.dto.response.PartShortageResponse;
import com.factoryflow.inventory.dto.response.PartStockResponse;
import com.factoryflow.inventory.service.PartLotService;
import com.factoryflow.inventory.service.PartService;
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

@Tag(name = "부품 재고", description = "부품 재고, 부족 부품, LOT 별 재고")
@RestController
@RequestMapping("/inventory/parts")
@RequiredArgsConstructor
public class PartController {

    private final PartService partService;
    private final PartLotService partLotService;

    @Operation(
            summary = "부품 재고 목록",
            description = "부품마다 바로 쓸 수 있는 수량과 생산에 잡힌 수량을 부품 코드 순으로 본다. 창고 실물은 둘의 합이다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 부품이 없으면 빈 목록")
    })
    @GetMapping
    public ApiResponse<List<PartStockResponse>> findParts() {
        List<PartStockResponse> parts = partService.findParts();
        return ApiResponse.ok(parts);
    }

    @Operation(
            summary = "부족 부품",
            description =
                    "기다리는 예약 수량이 바로 쓸 수 있는 수량보다 많은 부품을 부족 수량 큰 순으로 본다. 구매 담당이 보고 구매 건을 만든다. 이미 발주한 수량은 빼지 않으므로 발주 중인 구매 목록과 함께 본다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 부족한 부품이 없으면 빈 목록")
    })
    @GetMapping("/shortages")
    public ApiResponse<List<PartShortageResponse>> findShortages() {
        List<PartShortageResponse> shortages = partService.findShortages();
        return ApiResponse.ok(shortages);
    }

    @Operation(
            summary = "부품 LOT 별 재고",
            description =
                    "부품의 남은 LOT 을 다음에 쓸 순서(입고 순)로 본다. 처음부터 있던 재고는 초기 재고 LOT(LOT-INIT-{부품 id})이고 가장 먼저 쓴다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 남은 LOT 이 없으면 lots 는 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "partId 가 양수가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "부품 없음 (PART_NOT_FOUND)")
    })
    @GetMapping("/{partId}/lots")
    public ApiResponse<PartLotStockResponse> findLots(
            @Parameter(description = "부품 id", example = "1001") @PathVariable("partId") @Positive
                    Long partId) {
        PartLotStockResponse lots = partLotService.findLotsOfPart(partId);
        return ApiResponse.ok(lots);
    }

    @Operation(
            summary = "부품 등록",
            description = "새 부품을 등록한다. 재고 0 으로 시작하고 LOT 은 첫 입고 때 생긴다. 코드는 나중에 바꿀 수 없다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "부품 등록됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류 (코드·이름 없음·길이 초과, 단위가 EA·SET·KG 가 아님)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "같은 코드의 부품이 있음, 지운 부품 포함 (PART_CODE_DUPLICATED)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PartStockResponse> register(@Valid @RequestBody CreatePartRequest request) {
        PartStockResponse part = partService.register(request);
        return ApiResponse.created(part);
    }

    @Operation(
            summary = "부품 수정",
            description = "부품의 이름과 단위를 바꾼다. 코드와 수량은 바꾸지 않는다 (수량은 입고·예약·재고 조정으로만 바뀐다).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "부품 수정됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류 (이름 없음·길이 초과, 단위가 EA·SET·KG 가 아님, partId 가 양수가 아님)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "부품 없음 (PART_NOT_FOUND)")
    })
    @PutMapping("/{partId}")
    public ApiResponse<PartStockResponse> update(
            @Parameter(description = "부품 id", example = "1001") @PathVariable("partId") @Positive
                    Long partId,
            @Valid @RequestBody UpdatePartRequest request) {
        PartStockResponse part = partService.update(partId, request);
        return ApiResponse.ok(part);
    }
}

package com.factoryflow.inventory.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.inventory.dto.response.PartLotStockResponse;
import com.factoryflow.inventory.dto.response.PartStockResponse;
import com.factoryflow.inventory.service.PartLotService;
import com.factoryflow.inventory.service.PartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "부품 재고", description = "부품 재고와 LOT 별 재고")
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
            summary = "부품 LOT 별 재고",
            description =
                    "부품의 남은 LOT 을 다음에 쓸 순서(입고 순)로 본다. LOT 을 붙이기 전부터 있던 재고는 withoutLotQty 로 따로 보이고 생산에 먼저 쓴다.")
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
}

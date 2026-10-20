package com.factoryflow.production.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.production.dto.response.AwaitingReleaseResponse;
import com.factoryflow.production.dto.response.ReleaseWorkOrderResponse;
import com.factoryflow.production.service.ProductionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "생산 건", description = "차량 1대 단위 생산 건과 작업지시 발행")
@RestController
@RequestMapping("/production/productions")
@RequiredArgsConstructor
public class ProductionController {

    private final ProductionService productionService;

    @Operation(
            summary = "작업지시 발행 대기 목록",
            description =
                    "부품이 다 잡혀(READY) 작업지시 발행을 기다리는 생산 건을 먼저 생긴 순으로 id 커서(lastId, size)로 나눠 준다. 제품 코드·이름을 함께 준다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 기다리는 생산 건이 없으면 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "lastId 음수, size 1~100 밖")
    })
    @GetMapping("/awaiting-release")
    public ApiResponse<CursorPage<AwaitingReleaseResponse>> findAwaitingRelease(
            @Parameter(description = "이 id 다음부터 (처음이면 0)", example = "0")
                    @RequestParam(value = "lastId", defaultValue = "0")
                    @PositiveOrZero
                    Long lastId,
            @Parameter(description = "한 번에 받을 개수 (1~100)", example = "20")
                    @RequestParam(value = "size", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size) {
        CursorPage<AwaitingReleaseResponse> productions =
                productionService.findAwaitingRelease(lastId, size);
        return ApiResponse.ok(productions);
    }

    @Operation(
            summary = "작업지시 발행",
            description =
                    "부품이 다 잡힌(READY) 생산 건의 작업지시를 발행한다. 작업지시 1건과 공정 5개는 mes 가 이어서 만들고(production.released), 그 뒤 배정 스케줄러와 작업자가 공정을 시작할 수 있다. 발행한 차의 취소는 mes 가 첫 공정 시작 여부로 판단한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "202",
                description = "발행 접수함. 작업지시는 mes 작업지시 목록에서 본다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "productionId 가 양수가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "생산 건 없음 (PRODUCTION_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "READY 가 아님, 부품 확보 전이거나 이미 시작·완료·취소 (PRODUCTION_NOT_READY), 이미 발행함 (WORK_ORDER_ALREADY_RELEASED)")
    })
    @PostMapping("/{productionId}/work-order")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ReleaseWorkOrderResponse> release(
            @Parameter(description = "생산 건 번호", example = "101")
                    @PathVariable("productionId")
                    @Positive
                    Long productionId) {
        ReleaseWorkOrderResponse released = productionService.release(productionId);
        return ApiResponse.accepted(released);
    }
}

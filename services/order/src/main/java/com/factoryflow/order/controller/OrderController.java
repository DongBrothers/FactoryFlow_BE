package com.factoryflow.order.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.order.dto.request.CreateOrderRequest;
import com.factoryflow.order.dto.response.CreateOrderResponse;
import com.factoryflow.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "주문", description = "고객 주문")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(
            summary = "주문 생성",
            description =
                    "제품 여러 종을 한 번에 주문한다. 제품과 BOM 이 있는지 production 에 확인한 뒤 주문과 품목을 저장하고"
                            + " order.created 를 발행한다. 하나라도 문제가 있으면 주문 전체를 거절한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "주문 생성됨. 주문 번호를 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류 (X-User-Id 없음, 품목 없음, 수량 1 미만, 같은 제품 중복)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "없는 제품 (PRODUCT_NOT_FOUND) 또는 BOM 없는 제품 (BOM_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "production 응답 없음, 3초 초과, 본문이나 data 없는 응답 (PRODUCTION_UNAVAILABLE)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreateOrderResponse> create(
            @Parameter(description = "로그인한 고객 번호 (gateway 가 넣음)", example = "7")
                    @RequestHeader("X-User-Id")
                    Long customerId,
            @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderResponse response = orderService.create(customerId, request);
        return ApiResponse.created(response);
    }
}

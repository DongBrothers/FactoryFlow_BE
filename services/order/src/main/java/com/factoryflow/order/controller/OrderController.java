package com.factoryflow.order.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.order.dto.request.CreateOrderRequest;
import com.factoryflow.order.dto.response.CreateOrderResponse;
import com.factoryflow.order.dto.response.OrderDetailResponse;
import com.factoryflow.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "주문", description = "고객 주문")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    /** 주문 번호(UUID) 형식. */
    private static final String ORDER_NO =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

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
                description = "요청 형식 오류 (고객 번호 없음·1 미만, 품목 없음, 수량 1 미만, 같은 제품 중복)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "없는 제품 (PRODUCT_NOT_FOUND) 또는 BOM 없는 제품 (BOM_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "production 응답 없음, 3초 초과, 본문이나 data 없는 응답 (PRODUCTION_UNAVAILABLE)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreateOrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        CreateOrderResponse response = orderService.create(request);
        return ApiResponse.created(response);
    }

    @Operation(
            summary = "주문 상세",
            description = "주문 번호로 주문, 품목, 알려진 차량(생산 건이 생긴 것)의 상태와 차대번호를 본다. 내부 id 는 내보내지 않는다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "orderNo 가 UUID 형식이 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "주문 없음 (ORDER_NOT_FOUND)")
    })
    @GetMapping("/{orderNo}")
    public ApiResponse<OrderDetailResponse> findOrder(
            @Parameter(
                            description = "주문 번호 (UUID)",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable("orderNo")
                    @Pattern(regexp = ORDER_NO)
                    String orderNo) {
        OrderDetailResponse order = orderService.findOrder(orderNo);
        return ApiResponse.ok(order);
    }

    @Operation(
            summary = "주문 취소 요청",
            description =
                    "주문 전체 취소를 요청한다. 취소되지 않은 차량이 모두 취소 요청 중(CANCEL_REQUESTED)이 되고, 생산이 차량마다 판단한다: 프레스 배정 전이면 취소, 배정 후면 거절(원래 상태로 돌아감). 결과는 주문 상세로 본다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "취소 요청을 받음. 주문 상세를 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "orderNo 가 UUID 형식이 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "주문 없음 (ORDER_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "아직 생산 계획 중 (ORDER_NOT_READY), 완료·출고된 차량 있음 (ORDER_CANNOT_BE_CANCELLED), 이미 취소 요청 처리 중 (CANCEL_IN_PROGRESS), 모두 취소됨 (ORDER_ALREADY_CANCELLED), 다른 작업이 먼저 바꿈 (ORDER_CONFLICT)")
    })
    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<OrderDetailResponse> cancel(
            @Parameter(
                            description = "주문 번호 (UUID)",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable("orderNo")
                    @Pattern(regexp = ORDER_NO)
                    String orderNo) {
        OrderDetailResponse order = orderService.cancel(orderNo);
        return ApiResponse.ok(order);
    }
}

package com.factoryflow.order.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.order.dto.CreateOrderRequest;
import com.factoryflow.order.dto.CreateOrderResponse;
import com.factoryflow.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 고객 번호는 gateway 가 로그인 확인 후 X-User-Id 헤더로 넘긴다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreateOrderResponse> create(
            @RequestHeader("X-User-Id") Long customerId,
            @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.created(
                new CreateOrderResponse(orderService.create(customerId, request)));
    }
}

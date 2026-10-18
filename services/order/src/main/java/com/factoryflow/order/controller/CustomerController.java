package com.factoryflow.order.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.order.dto.request.CreateCustomerRequest;
import com.factoryflow.order.dto.response.CustomerResponse;
import com.factoryflow.order.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "고객", description = "주문하는 고객")
@RestController
@RequestMapping("/orders/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @Operation(
            summary = "고객 등록",
            description = "주문할 고객을 등록한다. 돌려준 customerId 를 주문 생성에 넣는다. 같은 이름도 등록할 수 있다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "고객 등록됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류 (이름 없음·공백·50자 초과, 연락처 100자 초과)")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CustomerResponse> register(
            @Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse customer = customerService.register(request);
        return ApiResponse.created(customer);
    }

    @Operation(summary = "고객 목록", description = "등록된 고객을 등록 순으로 한 번에 본다 (기준 정보라 페이지 없음).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 고객이 없으면 빈 목록")
    })
    @GetMapping
    public ApiResponse<List<CustomerResponse>> findCustomers() {
        List<CustomerResponse> customers = customerService.findCustomers();
        return ApiResponse.ok(customers);
    }
}

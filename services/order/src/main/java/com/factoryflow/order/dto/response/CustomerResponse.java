package com.factoryflow.order.dto.response;

import com.factoryflow.order.domain.Customer;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "고객")
public record CustomerResponse(
        @Schema(description = "고객 번호. 주문 생성의 customerId 로 쓴다", example = "7") Long customerId,
        @Schema(description = "고객 이름", example = "현대캐피탈") String name,
        @Schema(description = "연락처", example = "02-0000-0000") String contact) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getName(), customer.getContact());
    }
}

package com.factoryflow.order.exception;

import com.factoryflow.common.web.exception.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "주문한 제품을 찾을 수 없습니다."),
    BOM_NOT_FOUND(HttpStatus.NOT_FOUND, "부품 구성(BOM)이 없어 주문할 수 없는 제품입니다."),
    PRODUCTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "제품 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}

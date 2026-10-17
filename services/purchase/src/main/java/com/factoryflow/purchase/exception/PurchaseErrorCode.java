package com.factoryflow.purchase.exception;

import com.factoryflow.common.web.exception.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PurchaseErrorCode implements ErrorCode {
    PURCHASE_NOT_FOUND(HttpStatus.NOT_FOUND, "구매 건을 찾을 수 없습니다."),
    PART_NOT_FOUND(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."),
    INVENTORY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "부품 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    SUPPLIER_NOT_FOUND(HttpStatus.NOT_FOUND, "공급업체를 찾을 수 없습니다."),
    INVALID_PURCHASE_STATUS(HttpStatus.CONFLICT, "지금 구매 상태에서는 할 수 없는 작업입니다."),
    PURCHASE_CONFLICT(HttpStatus.CONFLICT, "다른 작업이 먼저 바꿨습니다. 다시 조회해 주세요.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}

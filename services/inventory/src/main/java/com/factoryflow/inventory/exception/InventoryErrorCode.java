package com.factoryflow.inventory.exception;

import com.factoryflow.common.web.exception.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum InventoryErrorCode implements ErrorCode {
    PART_NOT_FOUND(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "생산 건에 잡아 둔 부품이 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}

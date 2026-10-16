package com.factoryflow.simulator.exception;

import com.factoryflow.common.web.exception.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SimulatorErrorCode implements ErrorCode {
    EQUIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "설비를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}

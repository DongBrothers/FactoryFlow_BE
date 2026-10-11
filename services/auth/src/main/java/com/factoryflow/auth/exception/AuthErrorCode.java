package com.factoryflow.auth.exception;

import com.factoryflow.common.web.exception.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
    DUPLICATE_EMPLOYEE_NO("AUTH-001", "이미 등록된 사번입니다.", HttpStatus.CONFLICT),
    DUPLICATE_EMAIL("AUTH-002", "이미 등록된 이메일입니다.", HttpStatus.CONFLICT),
    INVALID_EMAIL_DOMAIN("AUTH-003", "허용되지 않은 이메일 도메인입니다.", HttpStatus.BAD_REQUEST),
    INVALID_ACTIVATION_TOKEN("AUTH-004", "유효하지 않거나 만료된 활성화 토큰입니다.", HttpStatus.BAD_REQUEST),
    ACCOUNT_NOT_INVITED("AUTH-005", "활성화할 수 없는 계정 상태입니다.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}

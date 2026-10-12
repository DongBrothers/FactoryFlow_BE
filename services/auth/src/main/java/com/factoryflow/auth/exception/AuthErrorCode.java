package com.factoryflow.auth.exception;

import com.factoryflow.common.web.exception.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
    DUPLICATE_EMAIL("AUTH-002", "이미 등록된 이메일입니다.", HttpStatus.CONFLICT),
    INVALID_EMAIL_DOMAIN("AUTH-003", "허용되지 않은 이메일 도메인입니다.", HttpStatus.BAD_REQUEST),
    INVALID_ACTIVATION_TOKEN("AUTH-004", "유효하지 않거나 만료된 활성화 토큰입니다.", HttpStatus.BAD_REQUEST),
    LOGIN_FAILED("AUTH-006", "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED),
    ACCOUNT_NOT_ACTIVE("AUTH-007", "비활성화된 계정입니다.", HttpStatus.FORBIDDEN),
    ACCOUNT_LOCKED("AUTH-008", "로그인 시도가 많아 일시적으로 잠겼습니다.", HttpStatus.TOO_MANY_REQUESTS),
    INVALID_REFRESH_TOKEN("AUTH-009", "유효하지 않은 리프레시 토큰입니다.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("AUTH-010", "권한이 없습니다.", HttpStatus.FORBIDDEN),
    TOKEN_SIGNING_FAILED("AUTH-011", "토큰을 발급하지 못했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}

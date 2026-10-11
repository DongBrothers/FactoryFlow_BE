package com.factoryflow.common.web.exception;

import com.factoryflow.common.web.response.ErrorResponse;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException e) {
        log.error("[Common] 비즈니스 예외: {}", e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(ErrorResponse.of(e.getErrorCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e) {
        log.error(
                "[Common] 유효성 검사 실패: {}",
                e.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField() + "(" + error.getCode() + ")")
                        .toList());
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder().code("VALIDATION_ERROR").message(message).build());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException e) {
        log.error("[Common] 유효성 검사 실패: {}", e.getMessage());
        String message = e.getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder().code("VALIDATION_ERROR").message(message).build());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException e) {
        log.error("[Common] 유효성 검사 실패: {}", e.getMessage());
        if (e.getCause() instanceof CustomException customException) {
            return ResponseEntity.status(customException.getErrorCode().getHttpStatus())
                    .body(ErrorResponse.of(customException.getErrorCode()));
        }
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.builder()
                                .code("VALIDATION_ERROR")
                                .message(e.getMessage())
                                .build());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeader(
            MissingRequestHeaderException e) {
        log.error("[Common] 필수 헤더 누락: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.builder()
                                .code("VALIDATION_ERROR")
                                .message(e.getHeaderName() + " 헤더가 필요합니다.")
                                .build());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParameter(
            MissingServletRequestParameterException e) {
        log.error("[Common] 필수 파라미터 누락: {}", e.getMessage());
        return badRequest(e.getParameterName() + " 파라미터가 필요합니다.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.error(
                "[Common] 파라미터 형식 오류: {} 는 {} 여야 한다",
                e.getName(),
                e.getRequiredType() == null ? "?" : e.getRequiredType().getSimpleName());
        return badRequest(e.getName() + " 값의 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        log.error("[Common] 요청 본문 읽기 실패: {}", e.getMostSpecificCause().getClass().getSimpleName());
        return badRequest("요청 본문을 읽을 수 없습니다.");
    }

    private static ResponseEntity<ErrorResponse> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder().code("VALIDATION_ERROR").message(message).build());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                        ErrorResponse.builder()
                                .code("NOT_FOUND")
                                .message("요청한 리소스를 찾을 수 없습니다.")
                                .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("[Common] 처리되지 않은 예외: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError()
                .body(
                        ErrorResponse.builder()
                                .code("INTERNAL_SERVER_ERROR")
                                .message("서버 내부 오류가 발생했습니다.")
                                .build());
    }
}

package com.factoryflow.common.web.response;

import lombok.Getter;

@Getter
public class ApiResponse<T> {
    private final T data;
    private final int status;
    private final String message;

    private ApiResponse(T data, int status, String message) {
        this.data = data;
        this.status = status;
        this.message = message;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, 200, "OK");
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(data, 201, "CREATED");
    }

    /** 요청을 받았고 처리는 다른 서비스가 이어서 한다 (이벤트로 넘김). */
    public static <T> ApiResponse<T> accepted(T data) {
        return new ApiResponse<>(data, 202, "ACCEPTED");
    }
}

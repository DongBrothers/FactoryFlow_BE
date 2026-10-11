package com.factoryflow.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 로그인 요청. 사번 + 비밀번호. */
public record LoginRequest(@NotBlank String employeeNo, @NotBlank String password) {}

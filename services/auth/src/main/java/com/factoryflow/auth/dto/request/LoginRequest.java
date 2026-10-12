package com.factoryflow.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 로그인 요청. 이메일 + 비밀번호. */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

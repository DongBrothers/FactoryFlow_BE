package com.factoryflow.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 로그아웃 요청. 폐기할 Refresh 토큰. */
public record LogoutRequest(@NotBlank String refreshToken) {}

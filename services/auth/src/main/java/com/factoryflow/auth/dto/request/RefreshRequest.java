package com.factoryflow.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Access 토큰 재발급 요청. */
public record RefreshRequest(@NotBlank String refreshToken) {}

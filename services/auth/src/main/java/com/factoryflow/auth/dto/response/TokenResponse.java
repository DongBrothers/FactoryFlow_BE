package com.factoryflow.auth.dto.response;

/** 발급된 토큰 쌍. */
public record TokenResponse(String accessToken, String refreshToken) {}

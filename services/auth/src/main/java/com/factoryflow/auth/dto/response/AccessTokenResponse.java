package com.factoryflow.auth.dto.response;

/** 로그인·재발급 응답 바디. Access 토큰만 담는다(Refresh 는 httpOnly 쿠키로 내린다). */
public record AccessTokenResponse(String accessToken) {}

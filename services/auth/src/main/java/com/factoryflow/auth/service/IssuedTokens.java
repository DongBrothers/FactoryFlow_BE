package com.factoryflow.auth.service;

/** 로그인·재발급으로 발급된 토큰 쌍. controller 가 Access 는 본문, Refresh 는 쿠키로 나눠 내린다(밖으로 그대로 나가지 않음). */
public record IssuedTokens(String accessToken, String refreshToken) {}

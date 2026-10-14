package com.factoryflow.auth.config;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Refresh 토큰을 httpOnly 쿠키로 만든다. JS 가 읽을 수 없어 XSS 로 탈취되지 않는다. 재발급·로그아웃 경로(/api/auth)에만 전송된다. 쿠키 경로는
 * 브라우저가 보는 외부 주소 기준이라 /api 를 붙인다 (gateway 가 /api 를 떼고 auth 의 /auth 로 넘긴다).
 * (docs/specs/auth-account.md)
 */
@Component
@RequiredArgsConstructor
public class RefreshCookieFactory {

    public static final String COOKIE_NAME = "refreshToken";
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthProperties authProperties;
    private final CookieProperties cookieProperties;

    /** 발급된 Refresh 토큰을 담은 쿠키. */
    public ResponseCookie create(String refreshToken) {
        return base(refreshToken, authProperties.refreshTokenTtl()).build();
    }

    /** 로그아웃 시 쿠키를 즉시 만료시키는 빈 쿠키. */
    public ResponseCookie expired() {
        return base("", Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite(cookieProperties.sameSite())
                .path(COOKIE_PATH)
                .maxAge(maxAge);
    }
}

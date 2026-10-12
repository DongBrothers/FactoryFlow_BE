package com.factoryflow.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Refresh 쿠키 설정. secure 는 HTTPS 전송 여부(로컬 http 는 false, 운영은 true), sameSite 는 CSRF 방어 수준.
 * (docs/specs/auth-account.md)
 */
@ConfigurationProperties(prefix = "auth.cookie")
public record CookieProperties(boolean secure, String sameSite) {}

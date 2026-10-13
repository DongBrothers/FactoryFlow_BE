package com.factoryflow.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auth 설정값 (application.yml 의 auth.*, docs/specs/auth-account.md). allowedEmailDomain 은 발급 가능한 이메일
 * 도메인(로컬·데모는 gmail.com, 운영은 회사 도메인), activationTokenTtl 은 활성화 토큰 유효 기간, accessTokenTtl 과
 * refreshTokenTtl 은 토큰 만료, maxLoginAttempts 와 loginLockWindow 는 로그인 실패 잠금 횟수와 시간이다.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String allowedEmailDomain,
        Duration activationTokenTtl,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        int maxLoginAttempts,
        Duration loginLockWindow) {}

package com.factoryflow.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auth 설정값. (docs/specs/auth-account.md)
 *
 * <ul>
 *   <li>allowedEmailDomain: 발급 가능한 이메일 도메인(로컬·데모는 gmail.com, 운영은 회사 도메인)
 *   <li>activationTokenTtl: 활성화 토큰 유효 기간
 *   <li>accessTokenTtl / refreshTokenTtl: Access/Refresh 토큰 만료
 *   <li>maxLoginAttempts / loginLockWindow: 로그인 실패 잠금 임계치와 창
 * </ul>
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String allowedEmailDomain,
        Duration activationTokenTtl,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        int maxLoginAttempts,
        Duration loginLockWindow) {}

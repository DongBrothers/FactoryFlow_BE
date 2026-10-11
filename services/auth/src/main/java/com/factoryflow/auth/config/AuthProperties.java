package com.factoryflow.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auth 설정값. allowedEmailDomain 은 발급 가능한 이메일 도메인(로컬·데모는 gmail.com, 운영은 회사 도메인), activationTokenTtl 은
 * 활성화 토큰 유효 기간. (docs/specs/auth-account.md)
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(String allowedEmailDomain, Duration activationTokenTtl) {}

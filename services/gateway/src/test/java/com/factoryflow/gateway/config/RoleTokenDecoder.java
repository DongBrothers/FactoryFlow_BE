package com.factoryflow.gateway.config;

import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 테스트용 토큰 해석기. auth 의 JWKS 를 부르지 않고, 토큰 문자열을 그대로 role 클레임으로 쓴다 ("Bearer ADMIN" → role=ADMIN).
 * "Bearer EXPIRED" 는 실제 해석기처럼 만료 오류를 낸다.
 */
@TestConfiguration
class RoleTokenDecoder {

    static final String EXPIRED = "EXPIRED";

    @Bean
    ReactiveJwtDecoder jwtDecoder() {
        return token -> {
            if (EXPIRED.equals(token)) {
                String message = "Jwt expired at 2026-01-01T00:00:00Z";
                return Mono.error(
                        new JwtValidationException(
                                message, List.of(new OAuth2Error("invalid_token", message, null))));
            }
            return Mono.just(
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject("test-user")
                            .claim("role", token)
                            .build());
        };
    }
}

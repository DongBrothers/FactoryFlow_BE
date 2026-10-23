package com.factoryflow.gateway.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 테스트용 토큰 해석기. auth 의 JWKS 를 부르지 않고, 토큰 문자열을 permissions 클레임으로 쓴다 ("Bearer READ+ORDER_WRITE" →
 * permissions=[READ, ORDER_WRITE]). "Bearer role-SALES" 는 permissions 없이 role 만 있는 옛 토큰이다. "Bearer
 * EXPIRED" 는 실제 해석기처럼 만료 오류를 낸다.
 */
@TestConfiguration
class PermissionTokenDecoder {

    static final String EXPIRED = "EXPIRED";

    /** permissions 클레임이 생기기 전(2026-10-23)에 받은 토큰. */
    static final String ROLE_ONLY = "role-";

    @Bean
    ReactiveJwtDecoder jwtDecoder() {
        return token -> {
            if (EXPIRED.equals(token)) {
                String message = "Jwt expired at 2026-01-01T00:00:00Z";
                return Mono.error(
                        new JwtValidationException(
                                message, List.of(new OAuth2Error("invalid_token", message, null))));
            }
            Jwt.Builder jwt = Jwt.withTokenValue(token).header("alg", "none").subject("test-user");
            if (token.startsWith(ROLE_ONLY)) {
                return Mono.just(jwt.claim("role", token.substring(ROLE_ONLY.length())).build());
            }
            return Mono.just(jwt.claim("permissions", Arrays.asList(token.split("\\+"))).build());
        };
    }
}

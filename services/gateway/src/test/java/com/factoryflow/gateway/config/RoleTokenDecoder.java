package com.factoryflow.gateway.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/** 테스트용 토큰 해석기. auth 의 JWKS 를 부르지 않고, 토큰 문자열을 그대로 role 클레임으로 쓴다 ("Bearer ADMIN" → role=ADMIN). */
@TestConfiguration
class RoleTokenDecoder {

    @Bean
    ReactiveJwtDecoder jwtDecoder() {
        return token ->
                Mono.just(
                        Jwt.withTokenValue(token)
                                .header("alg", "none")
                                .subject("test-user")
                                .claim("role", token)
                                .build());
    }
}

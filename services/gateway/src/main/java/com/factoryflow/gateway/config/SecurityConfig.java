package com.factoryflow.gateway.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * gateway 보안: auth 의 JWKS 로 Access 토큰 서명을 검증하고, JWT 의 role 클레임으로 접근을 제어한다. 로그인·활성화·재발급·로그아웃은 공개,
 * /api/auth/admin/** 은 ADMIN 만. JWKS 는 jwk-set-uri 로 auth 에서 직접 받으므로 밖으로 열지 않는다.
 * (docs/specs/auth-account.md)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(
                        exchange ->
                                exchange.pathMatchers("/actuator/health")
                                        .permitAll()
                                        .pathMatchers(
                                                HttpMethod.POST,
                                                "/api/auth/login",
                                                "/api/auth/activation",
                                                "/api/auth/token/refresh",
                                                "/api/auth/logout")
                                        .permitAll()
                                        .pathMatchers("/api/auth/admin/**")
                                        .hasRole("ADMIN")
                                        .anyExchange()
                                        .authenticated())
                .oauth2ResourceServer(
                        oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())))
                .build();
    }

    /** JWT 의 role 클레임("ADMIN")을 권한("ROLE_ADMIN")으로 변환한다. */
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> {
                    String role = jwt.getClaimAsString("role");
                    if (role == null) {
                        return List.<GrantedAuthority>of();
                    }
                    return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role));
                });
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}

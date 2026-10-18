package com.factoryflow.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * gateway 보안: auth 의 JWKS 로 Access 토큰 서명을 검증하고, JWT 의 role 클레임으로 접근을 제어한다. 로그인·활성화·재발급·로그아웃은 공개(토큰을
 * 검증하지 않음), /api/auth/admin/** 은 ADMIN 만(ADMIN 은 계정 발급만 한다). 조회(GET)는 업무
 * 역할(SALES·PURCHASE·PRODUCTION), 쓰기는 역할 표의 역할만, 표에 없는 요청은 아무도 못 한다
 * (docs/specs/role-permissions.md). 새 API 를 열려면 표와 여기에 더한다. 경로는 브라우저가 부르는 밖 주소 기준(라우트의 StripPrefix
 * 보다 먼저 돈다). JWKS 는 jwk-set-uri 로 auth 에서 직접 받으므로 밖으로 열지 않는다. (docs/specs/auth-account.md)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** 계정 발급만 하는 역할 (업무 조회·쓰기는 못 한다). */
    private static final String ADMIN = "ADMIN";

    private static final String SALES = "SALES";
    private static final String PURCHASE = "PURCHASE";

    /** 생산(공정 진행, 출고). 라인 작업자 WORKER 를 합쳤다 (#118). */
    private static final String PRODUCTION = "PRODUCTION";

    /** 로그인 없이 부르는 경로. 토큰이 붙어 있어도 검증하지 않는다. */
    private static final ServerWebExchangeMatcher PUBLIC_PATHS =
            new OrServerWebExchangeMatcher(
                    ServerWebExchangeMatchers.pathMatchers("/actuator/health"),
                    ServerWebExchangeMatchers.pathMatchers(
                            HttpMethod.POST,
                            "/api/auth/login",
                            "/api/auth/activation",
                            "/api/auth/token/refresh",
                            "/api/auth/logout"));

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, CorsConfigurationSource corsConfigurationSource) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(SecurityConfig::authorizeByRole)
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(GatewayErrors::unauthorized)
                                        .accessDeniedHandler(
                                                (exchange, denied) ->
                                                        GatewayErrors.write(
                                                                exchange, HttpStatus.FORBIDDEN)))
                .oauth2ResourceServer(
                        oauth ->
                                oauth.bearerTokenConverter(bearerTokenOutsidePublicPaths())
                                        .authenticationEntryPoint(GatewayErrors::unauthorized)
                                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())))
                .build();
    }

    /**
     * 역할 표 (docs/specs/role-permissions.md): 공개 경로, ADMIN 은 계정 발급만, 쓰기는 표의 역할만, 조회는 업무
     * 역할(SALES·PURCHASE· PRODUCTION)만, 표에 없는 요청은 아무도 못 한다.
     */
    private static void authorizeByRole(ServerHttpSecurity.AuthorizeExchangeSpec exchange) {
        exchange.matchers(PUBLIC_PATHS)
                .permitAll()
                .pathMatchers("/api/auth/admin/**")
                .hasRole(ADMIN);
        authorizeWrites(exchange);
        exchange.pathMatchers(HttpMethod.GET)
                .hasAnyRole(SALES, PURCHASE, PRODUCTION)
                .anyExchange()
                .denyAll();
    }

    /** 역할 표의 쓰기 요청. 규칙은 등록 순서대로 맞춰 보므로 읽기·거부 규칙보다 먼저 둔다. */
    private static void authorizeWrites(ServerHttpSecurity.AuthorizeExchangeSpec exchange) {
        exchange.pathMatchers(
                        HttpMethod.POST,
                        "/api/orders",
                        "/api/orders/*/cancel",
                        "/api/orders/customers")
                .hasRole(SALES)
                .pathMatchers(
                        HttpMethod.POST,
                        "/api/production/work-orders/*/steps/*/start",
                        "/api/production/work-orders/*/steps/*/complete",
                        "/api/inventory/finished-vehicles/*/ship",
                        "/api/production/products")
                .hasRole(PRODUCTION)
                .pathMatchers(HttpMethod.PUT, "/api/production/products/*")
                .hasRole(PRODUCTION)
                .pathMatchers(
                        HttpMethod.POST,
                        "/api/purchases",
                        "/api/purchases/*/receive",
                        "/api/purchases/suppliers",
                        "/api/inventory/parts",
                        "/api/inventory/parts/*/adjustments")
                .hasRole(PURCHASE)
                .pathMatchers(
                        HttpMethod.PUT, "/api/purchases/suppliers/*", "/api/inventory/parts/*")
                .hasRole(PURCHASE);
    }

    /**
     * 공개 경로에서는 Bearer 토큰을 읽지 않는다. 읽으면 만료된 Access 토큰이 붙은 재발급·로그아웃 요청도 permitAll 전에 401 로 막힌다 (#63).
     */
    private ServerAuthenticationConverter bearerTokenOutsidePublicPaths() {
        ServerBearerTokenAuthenticationConverter bearer =
                new ServerBearerTokenAuthenticationConverter();
        return exchange ->
                PUBLIC_PATHS
                        .matches(exchange)
                        .flatMap(
                                match -> match.isMatch() ? Mono.empty() : bearer.convert(exchange));
    }

    /**
     * 허용 origin 은 설정값(app.cors.allowed-origins). Refresh 토큰이 httpOnly 쿠키라 allowCredentials 를 켜고,
     * 그래서 origin 에 "*" 는 쓸 수 없다. Security 의 cors() 에 연결해 preflight(OPTIONS)가 인증 전에 응답된다.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
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

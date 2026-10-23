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
 * gateway 보안: auth 의 JWKS 로 Access 토큰 서명을 검증하고, 토큰의 role 로 역할 → 권한 표(RolePermissionTable, Redis 로
 * 받음)에서 권한을 찾아 접근을 제어한다. 역할 이름은 코드에 두지 않는다. 로그인·활성화·재발급·로그아웃은 공개(토큰을 검증하지 않음), 내 정보는 로그인한 직원 모두,
 * /api/auth/admin/** 은 ACCOUNT_ADMIN, 조회(GET)는 READ, 쓰기는 권한 표의 권한만, 표에 없는 요청은 아무도 못 한다
 * (docs/specs/role-permissions.md, role-permission-admin.md). 새 API 를 열려면 표와 여기에 더한다. 경로는 브라우저가 부르는
 * 밖 주소 기준(라우트의 StripPrefix 보다 먼저 돈다). JWKS 는 jwk-set-uri 로 auth 에서 직접 받으므로 밖으로 열지 않는다.
 * (docs/specs/auth-account.md)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * 권한 코드 (docs/specs/role-permissions.md). 역할이 어떤 권한을 갖는지는 auth DB(role_permission)가 정하고 Redis 로
     * 온다 (RolePermissionTable). 여기는 API 마다 필요한 권한만 둔다.
     */
    private static final String READ = "READ";

    private static final String ORDER_WRITE = "ORDER_WRITE";
    private static final String PRODUCTION_WRITE = "PRODUCTION_WRITE";
    private static final String PURCHASE_WRITE = "PURCHASE_WRITE";

    /** 계정 발급·역할 목록. 업무 조회·쓰기는 열지 않는다 (#118). */
    private static final String ACCOUNT_ADMIN = "ACCOUNT_ADMIN";

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
            ServerHttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            RolePermissionTable rolePermissionTable) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(SecurityConfig::authorizeByPermission)
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
                                        .jwt(
                                                jwt ->
                                                        jwt.jwtAuthenticationConverter(
                                                                jwtConverter(rolePermissionTable))))
                .build();
    }

    /**
     * 권한 표 (docs/specs/role-permissions.md): 공개 경로, 내 정보는 로그인만(권한 없이), 계정 관리는 ACCOUNT_ADMIN, 쓰기는 표의
     * 권한만, 조회는 READ, 표에 없는 요청은 아무도 못 한다. 역할 이름은 보지 않는다.
     */
    private static void authorizeByPermission(ServerHttpSecurity.AuthorizeExchangeSpec exchange) {
        exchange.matchers(PUBLIC_PATHS)
                .permitAll()
                .pathMatchers(HttpMethod.GET, "/api/auth/me")
                .authenticated()
                .pathMatchers("/api/auth/admin/**")
                .hasAuthority(ACCOUNT_ADMIN);
        authorizeWrites(exchange);
        exchange.pathMatchers(HttpMethod.GET).hasAuthority(READ).anyExchange().denyAll();
    }

    /** 권한 표의 쓰기 요청. 규칙은 등록 순서대로 맞춰 보므로 읽기·거부 규칙보다 먼저 둔다. */
    private static void authorizeWrites(ServerHttpSecurity.AuthorizeExchangeSpec exchange) {
        exchange.pathMatchers(
                        HttpMethod.POST,
                        "/api/orders",
                        "/api/orders/*/cancel",
                        "/api/orders/customers")
                .hasAuthority(ORDER_WRITE)
                .pathMatchers(
                        HttpMethod.POST,
                        "/api/mes/work-orders/*/steps/*/start",
                        "/api/mes/work-orders/*/steps/*/complete",
                        "/api/inventory/finished-vehicles/*/ship",
                        "/api/production/products",
                        "/api/production/productions/*/work-order")
                .hasAuthority(PRODUCTION_WRITE)
                .pathMatchers(HttpMethod.PUT, "/api/production/products/*")
                .hasAuthority(PRODUCTION_WRITE)
                .pathMatchers(
                        HttpMethod.POST,
                        "/api/purchases",
                        "/api/purchases/*/receive",
                        "/api/purchases/suppliers",
                        "/api/inventory/parts",
                        "/api/inventory/parts/*/adjustments")
                .hasAuthority(PURCHASE_WRITE)
                .pathMatchers(
                        HttpMethod.PUT, "/api/purchases/suppliers/*", "/api/inventory/parts/*")
                .hasAuthority(PURCHASE_WRITE);
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

    /**
     * 토큰의 role 클레임으로 역할 → 권한 표에서 권한을 찾는다. 표에 없는 역할이나 role 이 없는 토큰은 권한이 없다
     * (docs/specs/role-permission-admin.md).
     */
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtConverter(
            RolePermissionTable rolePermissionTable) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> rolePermissionTable.authorities(jwt.getClaimAsString("role")));
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}

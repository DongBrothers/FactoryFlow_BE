package com.factoryflow.gateway.config;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 뒤 서비스로 넘기는 사용자 헤더. 클라이언트가 보낸 X-User-* 는 모두 지우고, 검증한 JWT 의 sub(직원 public_id)로 X-User-Id 만 다시 채운다.
 * role 은 넘기지 않는다 (권한은 gateway 가 JWT 로 확인). 순서를 정하지 않아 Spring Security 필터(-100) 뒤에 돌므로, 인증된 요청이면
 * principal 이 있다. (docs/specs/auth-account.md "권한 확인과 헤더 신뢰 전제")
 */
@Component
public class UserHeaderFilter implements WebFilter {

    private static final String USER_ID = "X-User-Id";
    private static final String USER_HEADER_PREFIX = "X-User-";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(userId -> chain.filter(withUserHeaders(exchange, userId)));
    }

    private ServerWebExchange withUserHeaders(ServerWebExchange exchange, String userId) {
        ServerHttpRequest request =
                exchange.getRequest()
                        .mutate()
                        .headers(
                                headers -> {
                                    removeUserHeaders(headers);
                                    if (!userId.isEmpty()) {
                                        headers.set(USER_ID, userId);
                                    }
                                })
                        .build();
        return exchange.mutate().request(request).build();
    }

    private void removeUserHeaders(HttpHeaders headers) {
        List<String> names =
                headers.headerNames().stream().filter(UserHeaderFilter::isUserHeader).toList();
        names.forEach(headers::remove);
    }

    private static boolean isUserHeader(String name) {
        return name.regionMatches(true, 0, USER_HEADER_PREFIX, 0, USER_HEADER_PREFIX.length());
    }
}

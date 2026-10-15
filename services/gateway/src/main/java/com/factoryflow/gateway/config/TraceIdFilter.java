package com.factoryflow.gateway.config;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 요청마다 새 traceId 를 만든다. 클라이언트가 보낸 X-Trace-Id 는 버리고(밖에서 온 값은 믿지 않는다), 뒤 서비스로 X-Trace-Id 를 넘기고, 응답에
 * 같은 값을 붙인다. Spring Security 보다 먼저 돌아 gateway 가 만든 401·403 에도 붙는다
 * (docs/specs/saga-order-fulfillment.md "추적", #67).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements WebFilter {

    static final String HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set(HEADER, traceId);
        ServerHttpRequest request =
                exchange.getRequest()
                        .mutate()
                        .headers(headers -> headers.set(HEADER, traceId))
                        .build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}

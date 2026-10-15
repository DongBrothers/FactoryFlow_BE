package com.factoryflow.gateway.config;

import java.net.ConnectException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * gateway 의 404·5xx (라우트 없음, 뒤 서비스 연결 실패·시간 초과 등) 를 {"code", "message"} 로 응답한다. 상태 코드는 그대로 두되, 뒤
 * 서비스 연결 실패만 기본값 500 대신 503 으로 바꾼다. Boot 기본 처리기(Order -1) 보다 먼저 돈다. 서비스가 낸 오류 응답은 예외가 아니므로 여기 오지 않고
 * 그대로 전달된다 (#66).
 */
@Slf4j
@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatus status = statusOf(ex);
        if (status.is5xxServerError()) {
            log.error(
                    "[gateway] {} {}: {}",
                    status.value(),
                    exchange.getRequest().getPath(),
                    ex.toString());
        }
        return GatewayErrors.write(exchange, status);
    }

    /** 뒤 서비스 연결 실패(연결 거부)는 gateway 기본값 500 대신 503 으로 둔다. 그 밖에는 원래 상태를 그대로 쓴다. */
    private static HttpStatus statusOf(Throwable ex) {
        if (isConnectionFailure(ex)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static boolean isConnectionFailure(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }
}

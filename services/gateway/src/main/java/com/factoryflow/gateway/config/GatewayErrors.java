package com.factoryflow.gateway.config;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * gateway 가 직접 만드는 오류 응답. 서비스 공통 형식(common-web ErrorResponse)과 같은 {"code", "message"} 를 쓴다. code 는
 * 상태 이름, message 는 상태별 고정 문구이고 상태 코드는 바꾸지 않는다 (docs/specs/auth-account.md "gateway 오류 응답", #66).
 */
final class GatewayErrors {

    private GatewayErrors() {}

    /** 401. WWW-Authenticate 헤더(RFC 6750)를 붙이고, 토큰 오류면 error·error_description 을 담는다. */
    static Mono<Void> unauthorized(ServerWebExchange exchange, AuthenticationException ex) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate(ex));
        return write(exchange, HttpStatus.UNAUTHORIZED);
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body =
                "{\"code\":\"" + status.name() + "\",\"message\":\"" + message(status) + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static String message(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "로그인이 필요합니다.";
            case FORBIDDEN -> "권한이 없습니다.";
            case NOT_FOUND -> "요청한 리소스를 찾을 수 없습니다.";
            case INTERNAL_SERVER_ERROR -> "서버 내부 오류가 발생했습니다.";
            case SERVICE_UNAVAILABLE -> "서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
            case GATEWAY_TIMEOUT -> "서비스 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.";
            default -> "요청을 처리할 수 없습니다.";
        };
    }

    private static String wwwAuthenticate(AuthenticationException ex) {
        if (!(ex instanceof OAuth2AuthenticationException oauth)) {
            return "Bearer";
        }
        OAuth2Error error = oauth.getError();
        String header = "Bearer error=\"" + error.getErrorCode() + "\"";
        if (error.getDescription() != null) {
            header += ", error_description=\"" + error.getDescription().replace("\"", "'") + "\"";
        }
        return header;
    }
}

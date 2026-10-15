package com.factoryflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

/**
 * 실제 gateway 로 만들기 어려운 500·504·그 밖의 상태의 본문. 오류 처리기에 예외를 직접 넣어 명세 "gateway 오류 응답" 표의 code·message 를
 * 확인한다 (401·403·404·503 은 SecurityConfigTest, GatewayErrorResponseTest, #66).
 */
class GatewayErrorWebExceptionHandlerTest {

    private final GatewayErrorWebExceptionHandler handler = new GatewayErrorWebExceptionHandler();

    @Test
    void unexpectedErrorIsInternalServerError() {
        assertResponse(
                new IllegalStateException("boom"),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}");
    }

    @Test
    void timeoutIsGatewayTimeout() {
        assertResponse(
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT),
                HttpStatus.GATEWAY_TIMEOUT,
                "{\"code\":\"GATEWAY_TIMEOUT\","
                        + "\"message\":\"서비스 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.\"}");
    }

    @Test
    void otherStatusUsesStatusNameAndGenericMessage() {
        assertResponse(
                new ResponseStatusException(HttpStatus.BAD_REQUEST),
                HttpStatus.BAD_REQUEST,
                "{\"code\":\"BAD_REQUEST\",\"message\":\"요청을 처리할 수 없습니다.\"}");
    }

    private void assertResponse(Throwable ex, HttpStatus status, String body) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders/1"));

        handler.handle(exchange, ex).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(body);
    }
}

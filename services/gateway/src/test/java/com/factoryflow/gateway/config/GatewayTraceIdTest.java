package com.factoryflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * gateway 의 traceId. 요청마다 새 traceId 를 만들어 뒤 서비스로 X-Trace-Id 로 넘기고, 모든 응답(gateway 가 만든 오류 포함)에 같은 값을
 * 붙인다. 클라이언트가 보낸 X-Trace-Id 는 버린다 (#67, docs/specs/saga-order-fulfillment.md "추적").
 */
@ExtendWith(OutputCaptureExtension.class)
class GatewayTraceIdTest extends GatewayTestSupport {

    private static final String TRACE_ID = "X-Trace-Id";

    @Test
    void replacesClientTraceIdAndReturnsTheForwardedOne() {
        EntityExchangeResult<byte[]> result =
                client.get()
                        .uri("/api/orders/1")
                        .header("Authorization", "Bearer SALES")
                        .header(TRACE_ID, "client-trace")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult();

        String forwarded = BACKEND.lastTraceId();
        assertThat(isUuid(forwarded)).isTrue();
        assertThat(result.getResponseHeaders().getFirst(TRACE_ID)).isEqualTo(forwarded);
    }

    /** 뒤 서비스도 응답에 X-Trace-Id 를 넣는다. 최종 응답에는 하나만 있어야 한다 (#82). */
    @Test
    void responseHasOneTraceIdEvenWhenServiceAlsoSetsIt() {
        EntityExchangeResult<byte[]> result =
                client.get()
                        .uri("/api/orders/1")
                        .header("Authorization", "Bearer SALES")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult();

        assertThat(result.getResponseHeaders().get(TRACE_ID))
                .containsExactly(BACKEND.lastTraceId());
    }

    @Test
    void publicPathAlsoGetsTraceId() {
        EntityExchangeResult<byte[]> result =
                client.post()
                        .uri("/api/auth/login")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult();

        assertThat(isUuid(BACKEND.lastTraceId())).isTrue();
        assertThat(result.getResponseHeaders().getFirst(TRACE_ID)).isEqualTo(BACKEND.lastTraceId());
    }

    @Test
    void eachRequestGetsItsOwnTraceId() {
        String first = traceIdOf("/api/auth/login");
        String second = traceIdOf("/api/auth/login");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void unauthorizedResponseCarriesTraceId() {
        String traceId =
                client.get()
                        .uri("/api/orders/1")
                        .exchange()
                        .expectStatus()
                        .isUnauthorized()
                        .expectBody()
                        .returnResult()
                        .getResponseHeaders()
                        .getFirst(TRACE_ID);

        assertThat(isUuid(traceId)).isTrue();
    }

    @Test
    void notFoundResponseCarriesTraceId() {
        String traceId =
                client.get()
                        .uri("/api/unknown/1")
                        .header("Authorization", "Bearer SALES")
                        .exchange()
                        .expectStatus()
                        .isNotFound()
                        .expectBody()
                        .returnResult()
                        .getResponseHeaders()
                        .getFirst(TRACE_ID);

        assertThat(isUuid(traceId)).isTrue();
    }

    /** gateway 로그는 MDC 가 스레드를 따라가지 않으므로, 오류 처리기가 traceId 를 메시지에 직접 넣는다. */
    @Test
    void serviceUnavailableResponseAndLogCarryTraceId(CapturedOutput output) {
        String traceId =
                client.get()
                        .uri("/api/down/1")
                        .header("Authorization", "Bearer SALES")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(503)
                        .expectBody()
                        .returnResult()
                        .getResponseHeaders()
                        .getFirst(TRACE_ID);

        assertThat(isUuid(traceId)).isTrue();
        assertThat(output).contains("traceId=" + traceId);
    }

    private String traceIdOf(String path) {
        return client.post()
                .uri(path)
                .exchange()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getFirst(TRACE_ID);
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

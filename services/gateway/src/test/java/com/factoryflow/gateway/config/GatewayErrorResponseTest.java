package com.factoryflow.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.json.JsonCompareMode;

/**
 * gateway 가 직접 만든 404·5xx 도 서비스와 같은 {code, message} 형식이고, 상태 코드는 그대로다(뒤 서비스 연결 실패만 503). 서비스가 낸 오류는
 * 상태·본문을 바꾸지 않고 전달한다 (#66, docs/specs/auth-account.md "gateway 오류 응답").
 */
class GatewayErrorResponseTest extends GatewayTestSupport {

    @Test
    void unknownRouteReturnsNotFoundInCommonFormat() {
        client.get()
                .uri("/api/unknown/1")
                .header("Authorization", "Bearer SALES")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .json(
                        "{\"code\":\"NOT_FOUND\",\"message\":\"요청한 리소스를 찾을 수 없습니다.\"}",
                        JsonCompareMode.STRICT);
    }

    /** 뒤 서비스가 꺼져 연결이 거부되면 503. gateway 기본값은 500(내부 오류)이라 의미가 틀려 바꿨다 (#66). */
    @Test
    void unreachableServiceReturnsServiceUnavailable() {
        client.get()
                .uri("/api/down/1")
                .header("Authorization", "Bearer SALES")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .json(
                        "{\"code\":\"SERVICE_UNAVAILABLE\","
                                + "\"message\":\"서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.\"}",
                        JsonCompareMode.STRICT);
    }

    @Test
    void serviceErrorPassesThroughUnchanged() {
        client.get()
                .uri("/api/orders/error")
                .header("Authorization", "Bearer SALES")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .json(StubBackend.SERVICE_ERROR_BODY, JsonCompareMode.STRICT);
    }
}

package com.factoryflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * gateway 가 뒤 서비스로 넘기는 X-User-* 헤더. 클라이언트가 보낸 X-User-* 는 지우고, 검증한 JWT 의 sub 로 X-User-Id 만 다시 채운다.
 * 실제 라우트로 StubBackend 까지 보내, 뒤 서비스가 받은 헤더를 확인한다. (docs/specs/auth-account.md "권한 확인과 헤더 신뢰 전제")
 */
class UserHeaderFilterTest extends GatewayTestSupport {

    @Test
    void replacesForgedUserIdWithJwtSubject() {
        client.get()
                .uri("/api/orders/1")
                .header("Authorization", "Bearer SALES")
                .header("X-User-Id", "forged-user")
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(BACKEND.lastPath()).isEqualTo("/orders/1");
        assertThat(BACKEND.lastUserHeaders()).isEqualTo(Map.of("x-user-id", "test-user"));
    }

    @Test
    void removesOtherUserHeadersRegardlessOfCase() {
        client.get()
                .uri("/api/orders/1")
                .header("Authorization", "Bearer SALES")
                .header("x-user-role", "ADMIN")
                .header("X-USER-NAME", "forged")
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(BACKEND.lastUserHeaders()).isEqualTo(Map.of("x-user-id", "test-user"));
    }

    @Test
    void publicPathPassesNoUserHeader() {
        client.post()
                .uri("/api/auth/login")
                .header("X-User-Id", "forged-user")
                .header("X-User-Role", "ADMIN")
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(BACKEND.lastPath()).isEqualTo("/auth/login");
        assertThat(BACKEND.lastUserHeaders()).isEmpty();
    }
}

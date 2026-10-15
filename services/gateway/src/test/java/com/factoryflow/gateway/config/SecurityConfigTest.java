package com.factoryflow.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * gateway 의 권한 확인. admin API 는 JWT 의 role 이 ADMIN 일 때만 통과한다 (auth 는 role 을 다시 확인하지 않는다). 라우트가 없으므로
 * 통과하면 404, 막히면 401·403 이다. 토큰 해석만 RoleTokenDecoder 로 바꾸고, role → 권한 변환은 SecurityConfig 그대로 쓴다.
 * (docs/specs/auth-account.md)
 */
@WebFluxTest
@Import({SecurityConfig.class, RoleTokenDecoder.class})
class SecurityConfigTest {

    @Autowired WebTestClient client;

    @Test
    void adminApiRejectsMissingToken() {
        client.post().uri("/api/auth/admin/users").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void adminApiRejectsNonAdminRole() {
        callAdminApi("SALES").expectStatus().isForbidden();
    }

    @Test
    void adminApiAllowsAdminRole() {
        callAdminApi("ADMIN").expectStatus().isNotFound();
    }

    @Test
    void loginIsPublic() {
        client.post().uri("/api/auth/login").exchange().expectStatus().isNotFound();
    }

    /** CORS 허용 origin 의 기본값은 비어 있다. 환경별 설정 없이는 어떤 origin 의 preflight 도 403 이다. */
    @Test
    void corsAllowsNoOriginByDefault() {
        client.options()
                // CORS 는 요청 주소의 origin 과 비교하므로 host 가 있어야 한다 (없으면 origin 과 상관없이 403)
                .uri("https://api.factoryflow.test/api/orders")
                .header("Origin", "https://factoryflow.dev")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    private WebTestClient.ResponseSpec callAdminApi(String role) {
        return client.post()
                .uri("/api/auth/admin/users")
                .header("Authorization", "Bearer " + role)
                .exchange();
    }
}

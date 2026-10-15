package com.factoryflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * gateway CORS. 허용 origin 만 통과하고, Refresh 토큰이 httpOnly 쿠키라 allowCredentials 가 켜져 있어야 한다. 브라우저가 먼저
 * 보내는 preflight(OPTIONS)는 토큰을 싣지 않으므로 인증 없이 gateway 가 바로 응답한다 (뒤 서비스로 가지 않음).
 * (docs/specs/auth-account.md "gateway 배선 PR 체크리스트")
 */
class CorsTest extends GatewayTestSupport {

    @Test
    void preflightFromAllowedOriginPassesWithoutToken() {
        preflight(ALLOWED_ORIGIN)
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", ALLOWED_ORIGIN)
                .expectHeader()
                .valueEquals("Access-Control-Allow-Credentials", "true");

        assertThat(BACKEND.lastPath()).isNull();
    }

    @Test
    void preflightFromOtherOriginIsRejected() {
        preflight("https://evil.example").expectStatus().isForbidden();

        assertThat(BACKEND.lastPath()).isNull();
    }

    @Test
    void routedResponseToAllowedOriginAllowsCredentials() {
        client.post()
                .uri("/api/auth/token/refresh")
                .header("Origin", ALLOWED_ORIGIN)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", ALLOWED_ORIGIN)
                .expectHeader()
                .valueEquals("Access-Control-Allow-Credentials", "true");

        assertThat(BACKEND.lastPath()).isEqualTo("/auth/token/refresh");
    }

    private WebTestClient.ResponseSpec preflight(String origin) {
        return client.options()
                .uri("/api/orders")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", HttpMethod.POST.name())
                .header("Access-Control-Request-Headers", "Authorization, Content-Type")
                .exchange();
    }
}

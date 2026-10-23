package com.factoryflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * gateway 의 권한 확인. admin API 는 토큰의 role 이 역할 → 권한 표에서 ACCOUNT_ADMIN 을 가질 때만 통과한다 (auth 는 다시 확인하지
 * 않는다). 라우트가 없으므로 통과하면 404, 막히면 401·403 이다. 토큰 해석만 RoleTokenDecoder 로 바꾸고, 클레임 → 권한 변환은
 * SecurityConfig 그대로 쓴다. (docs/specs/auth-account.md)
 */
@WebFluxTest
@Import({SecurityConfig.class, RoleTokenDecoder.class, TestRolePermissions.class})
class SecurityConfigTest {

    private static final String UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요합니다.\"}";

    @Autowired WebTestClient client;

    @Test
    void adminApiRejectsMissingToken() {
        client.post().uri("/api/auth/admin/users").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void adminApiRejectsWithoutAccountAdmin() {
        callAdminApi("READ+ORDER_WRITE").expectStatus().isForbidden();
    }

    @Test
    void adminApiAllowsAccountAdmin() {
        callAdminApi("ACCOUNT_ADMIN").expectStatus().isNotFound();
    }

    /**
     * gateway 는 역할 이름을 모르고 권한 코드만 본다. 역할별 권한은 auth DB(role_permission)가 정한다
     * (docs/specs/role-permissions.md). 역할 이름이 다시 들어오면 역할을 더할 때 gateway 도 고쳐야 한다.
     */
    @Test
    void securityConfigDoesNotNameRoles() throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/factoryflow/gateway/config/SecurityConfig.java"));

        // 단어 경계라 권한 코드(PURCHASE_WRITE, ACCOUNT_ADMIN 등)는 걸리지 않는다
        assertThat(source)
                .doesNotContainPattern("\\b(SALES|PURCHASE|PRODUCTION|ADMIN|WORKER)\\b")
                .doesNotContain("ROLE_", "hasRole");
    }

    @Test
    void loginIsPublic() {
        client.post().uri("/api/auth/login").exchange().expectStatus().isNotFound();
    }

    /**
     * 공개 경로는 토큰을 검증하지 않는다. 프론트가 만료된 Access 토큰을 붙인 채 재발급·로그아웃을 불러도 auth 까지 가야 한다 (#63). 라우트가 없으므로
     * 통과하면 404 다.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/auth/login",
                "/api/auth/activation",
                "/api/auth/token/refresh",
                "/api/auth/logout"
            })
    void publicPathIgnoresExpiredToken(String path) {
        client.post()
                .uri(path)
                .header("Authorization", "Bearer " + RoleTokenDecoder.EXPIRED)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void protectedPathRejectsExpiredToken() {
        client.get()
                .uri("/api/orders/1")
                .header("Authorization", "Bearer " + RoleTokenDecoder.EXPIRED)
                .exchange()
                .expectStatus()
                .isUnauthorized();
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

    /**
     * gateway 가 만든 401·403 도 서비스와 같은 {code, message} 형식이다 (#66). 401 의 WWW-Authenticate 헤더는 그대로다.
     */
    @Test
    void missingTokenBodyIsCommonFormat() {
        client.post()
                .uri("/api/auth/admin/users")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueMatches("WWW-Authenticate", "Bearer.*")
                .expectBody()
                .json(UNAUTHORIZED_BODY, JsonCompareMode.STRICT);
    }

    @Test
    void expiredTokenBodyIsCommonFormat() {
        client.get()
                .uri("/api/orders/1")
                .header("Authorization", "Bearer " + RoleTokenDecoder.EXPIRED)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueMatches("WWW-Authenticate", "Bearer.*invalid_token.*")
                .expectBody()
                .json(UNAUTHORIZED_BODY, JsonCompareMode.STRICT);
    }

    @Test
    void forbiddenBodyIsCommonFormat() {
        callAdminApi("READ+ORDER_WRITE")
                .expectStatus()
                .isForbidden()
                .expectBody()
                .json("{\"code\":\"FORBIDDEN\",\"message\":\"권한이 없습니다.\"}", JsonCompareMode.STRICT);
    }

    private WebTestClient.ResponseSpec callAdminApi(String permissions) {
        return client.post()
                .uri("/api/auth/admin/users")
                .header("Authorization", "Bearer " + permissions)
                .exchange();
    }
}

package com.factoryflow.gateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 역할별 API 권한 (docs/specs/role-permissions.md). 조회(GET)는 로그인한 직원 모두, 쓰기는 역할 표의 역할과 ADMIN 만, 표에 없는
 * 쓰기는 ADMIN 만. 라우트가 없으므로 통과하면 404, 막히면 401·403 이다.
 */
@WebFluxTest
@Import({SecurityConfig.class, RoleTokenDecoder.class})
class RolePermissionTest {

    private static final String ORDER_NO = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VIN = "KMH000017TU000012";

    @Autowired WebTestClient client;

    @ParameterizedTest(name = "{1} 는 {0} 을 할 수 있다")
    @CsvSource({
        "/api/orders, SALES",
        "/api/orders/" + ORDER_NO + "/cancel, SALES",
        "/api/orders/customers, SALES",
        "/api/production/work-orders/1/steps/PRESS/start, PRODUCTION",
        "/api/production/work-orders/1/steps/INSPECTION/complete, PRODUCTION",
        "/api/purchases, PURCHASE",
        "/api/purchases/1/receive, PURCHASE",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, PRODUCTION"
    })
    void allowedRoleCanWrite(String path, String role) {
        post(path, role).expectStatus().isNotFound();
    }

    @ParameterizedTest(name = "{1} 는 {0} 을 할 수 없다")
    @CsvSource({
        "/api/orders, PRODUCTION",
        "/api/orders, PURCHASE",
        "/api/orders, ADMIN",
        "/api/orders/" + ORDER_NO + "/cancel, ADMIN",
        "/api/orders/customers, PURCHASE",
        "/api/orders/customers, PRODUCTION",
        "/api/orders/customers, ADMIN",
        "/api/production/work-orders/1/steps/PRESS/start, SALES",
        "/api/production/work-orders/1/steps/PRESS/start, PURCHASE",
        "/api/production/work-orders/1/steps/PRESS/start, ADMIN",
        "/api/production/work-orders/1/steps/INSPECTION/complete, SALES",
        "/api/purchases, SALES",
        "/api/purchases, PRODUCTION",
        "/api/purchases, ADMIN",
        "/api/purchases/1/receive, ADMIN",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, SALES",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, PURCHASE",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, ADMIN"
    })
    void otherRoleCannotWrite(String path, String role) {
        post(path, role).expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "합쳐 없어진 WORKER 토큰은 {0} 을 할 수 없다")
    @ValueSource(
            strings = {
                "/api/production/work-orders/1/steps/PRESS/start",
                "/api/production/work-orders/1/steps/INSPECTION/complete",
                "/api/inventory/finished-vehicles/" + VIN + "/ship"
            })
    void mergedWorkerRoleHasNoPermission(String path) {
        // WORKER 는 PRODUCTION 으로 합쳤다 (#118). 합치기 전에 받은 토큰은 다시 로그인해야 한다
        post(path, "WORKER").expectStatus().isForbidden();
        request(HttpMethod.GET, "/api/production/work-orders", "WORKER")
                .expectStatus()
                .isForbidden();
    }

    @ParameterizedTest(name = "표에 없는 요청은 {0} 도 할 수 없다")
    @ValueSource(strings = {"SALES", "PURCHASE", "PRODUCTION", "ADMIN"})
    void requestNotInTheTableIsDeniedToEveryone(String role) {
        post("/api/inventory/parts", role).expectStatus().isForbidden();
        request(HttpMethod.PUT, "/api/orders/" + ORDER_NO, role).expectStatus().isForbidden();
        request(HttpMethod.DELETE, "/api/purchases/1", role).expectStatus().isForbidden();
    }

    @Test
    void adminOnlyIssuesAccounts() {
        // ADMIN 은 계정 발급만 한다. 업무 조회도 못 한다 (#118)
        post("/api/auth/admin/users", "ADMIN").expectStatus().isNotFound();
        for (String path : readPaths()) {
            request(HttpMethod.GET, path, "ADMIN").expectStatus().isForbidden();
        }
    }

    @ParameterizedTest(name = "{0} 는 조회할 수 있다")
    @ValueSource(strings = {"SALES", "PURCHASE", "PRODUCTION"})
    void businessRolesCanRead(String role) {
        for (String path : readPaths()) {
            request(HttpMethod.GET, path, role).expectStatus().isNotFound();
        }
    }

    private static String[] readPaths() {
        return new String[] {
            "/api/orders",
            "/api/orders/customers",
            "/api/purchases?status=ORDERED",
            "/api/production/work-orders",
            "/api/inventory/parts"
        };
    }

    @Test
    void readingStillNeedsLogin() {
        client.get().uri("/api/inventory/parts").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/orders").exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec post(String path, String role) {
        return request(HttpMethod.POST, path, role);
    }

    private WebTestClient.ResponseSpec request(HttpMethod method, String path, String role) {
        return client.method(method).uri(path).header("Authorization", "Bearer " + role).exchange();
    }
}

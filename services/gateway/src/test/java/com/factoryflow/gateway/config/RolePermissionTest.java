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
        "/api/orders, ADMIN",
        "/api/orders/" + ORDER_NO + "/cancel, SALES",
        "/api/orders/" + ORDER_NO + "/cancel, ADMIN",
        "/api/production/work-orders/1/steps/PRESS/start, WORKER",
        "/api/production/work-orders/1/steps/PRESS/start, PRODUCTION",
        "/api/production/work-orders/1/steps/PRESS/start, ADMIN",
        "/api/production/work-orders/1/steps/INSPECTION/complete, WORKER",
        "/api/production/work-orders/1/steps/INSPECTION/complete, PRODUCTION",
        "/api/production/work-orders/1/steps/INSPECTION/complete, ADMIN",
        "/api/purchases, PURCHASE",
        "/api/purchases, ADMIN",
        "/api/purchases/1/receive, PURCHASE",
        "/api/purchases/1/receive, ADMIN",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, PRODUCTION",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, ADMIN"
    })
    void allowedRoleCanWrite(String path, String role) {
        post(path, role).expectStatus().isNotFound();
    }

    @ParameterizedTest(name = "{1} 는 {0} 을 할 수 없다")
    @CsvSource({
        "/api/orders, WORKER",
        "/api/orders, PRODUCTION",
        "/api/orders, PURCHASE",
        "/api/orders/" + ORDER_NO + "/cancel, WORKER",
        "/api/production/work-orders/1/steps/PRESS/start, SALES",
        "/api/production/work-orders/1/steps/PRESS/start, PURCHASE",
        "/api/production/work-orders/1/steps/INSPECTION/complete, SALES",
        "/api/purchases, SALES",
        "/api/purchases, PRODUCTION",
        "/api/purchases/1/receive, WORKER",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, SALES",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, WORKER",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, PURCHASE"
    })
    void otherRoleCannotWrite(String path, String role) {
        post(path, role).expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "표에 없는 쓰기는 {0} 이 할 수 없다")
    @ValueSource(strings = {"SALES", "PURCHASE", "PRODUCTION", "WORKER"})
    void writeNotInTheTableIsAdminOnly(String role) {
        post("/api/inventory/parts", role).expectStatus().isForbidden();
        request(HttpMethod.PUT, "/api/orders/" + ORDER_NO, role).expectStatus().isForbidden();
        request(HttpMethod.DELETE, "/api/purchases/1", role).expectStatus().isForbidden();
    }

    @Test
    void adminCanWriteWhatIsNotInTheTable() {
        post("/api/inventory/parts", "ADMIN").expectStatus().isNotFound();
        request(HttpMethod.PUT, "/api/orders/" + ORDER_NO, "ADMIN").expectStatus().isNotFound();
    }

    @ParameterizedTest(name = "{0} 는 조회할 수 있다")
    @ValueSource(strings = {"SALES", "PURCHASE", "PRODUCTION", "WORKER", "ADMIN"})
    void everyRoleCanRead(String role) {
        for (String path :
                new String[] {
                    "/api/orders",
                    "/api/purchases?status=ORDERED",
                    "/api/production/work-orders",
                    "/api/inventory/parts"
                }) {
            request(HttpMethod.GET, path, role).expectStatus().isNotFound();
        }
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

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
 * API 권한 (docs/specs/role-permissions.md, role-permission-admin.md). gateway 는 토큰의 role 로 역할 → 권한
 * 표에서 권한을 찾는다. 조회(GET)는 READ, 쓰기는 표의 권한만, 표에 없는 요청은 아무도 못 한다. 테스트 역할 이름은 가진 권한을 + 로 이은 것이다
 * (TestRolePermissions). 라우트가 없으므로 통과하면 404, 막히면 401·403 이다.
 */
@WebFluxTest
@Import({SecurityConfig.class, RoleTokenDecoder.class, TestRolePermissions.class})
class RolePermissionTest {

    private static final String ORDER_NO = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VIN = "KMH000017TU000012";

    @Autowired WebTestClient client;

    @ParameterizedTest(name = "{1} 권한으로 {0} 을 할 수 있다")
    @CsvSource({
        "/api/orders, ORDER_WRITE",
        "/api/orders/" + ORDER_NO + "/cancel, ORDER_WRITE",
        "/api/orders/customers, ORDER_WRITE",
        "/api/mes/work-orders/1/steps/PRESS/start, PRODUCTION_WRITE",
        "/api/mes/work-orders/1/steps/INSPECTION/complete, PRODUCTION_WRITE",
        "/api/purchases, PURCHASE_WRITE",
        "/api/purchases/1/receive, PURCHASE_WRITE",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, PRODUCTION_WRITE",
        "/api/inventory/parts, PURCHASE_WRITE",
        "/api/purchases/suppliers, PURCHASE_WRITE",
        "/api/production/products, PRODUCTION_WRITE",
        "/api/inventory/parts/1001/adjustments, PURCHASE_WRITE",
        "/api/production/productions/101/work-order, PRODUCTION_WRITE"
    })
    void allowedPermissionCanWrite(String path, String permission) {
        post(path, permission).expectStatus().isNotFound();
    }

    @ParameterizedTest(name = "{1} 권한으로 {0} 을 할 수 없다")
    @CsvSource({
        "/api/orders, PRODUCTION_WRITE",
        "/api/orders, PURCHASE_WRITE",
        "/api/orders, ACCOUNT_ADMIN",
        "/api/orders/" + ORDER_NO + "/cancel, ACCOUNT_ADMIN",
        "/api/orders/customers, PURCHASE_WRITE",
        "/api/orders/customers, PRODUCTION_WRITE",
        "/api/orders/customers, ACCOUNT_ADMIN",
        "/api/mes/work-orders/1/steps/PRESS/start, ORDER_WRITE",
        "/api/mes/work-orders/1/steps/PRESS/start, PURCHASE_WRITE",
        "/api/mes/work-orders/1/steps/PRESS/start, ACCOUNT_ADMIN",
        "/api/mes/work-orders/1/steps/INSPECTION/complete, ORDER_WRITE",
        "/api/purchases, ORDER_WRITE",
        "/api/purchases, PRODUCTION_WRITE",
        "/api/purchases, ACCOUNT_ADMIN",
        "/api/purchases/1/receive, ACCOUNT_ADMIN",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, ORDER_WRITE",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, PURCHASE_WRITE",
        "/api/inventory/finished-vehicles/" + VIN + "/ship, ACCOUNT_ADMIN",
        "/api/inventory/parts, ORDER_WRITE",
        "/api/inventory/parts, PRODUCTION_WRITE",
        "/api/inventory/parts, ACCOUNT_ADMIN",
        "/api/purchases/suppliers, ORDER_WRITE",
        "/api/purchases/suppliers, PRODUCTION_WRITE",
        "/api/purchases/suppliers, ACCOUNT_ADMIN",
        "/api/production/products, ORDER_WRITE",
        "/api/production/products, PURCHASE_WRITE",
        "/api/production/products, ACCOUNT_ADMIN",
        "/api/inventory/parts/1001/adjustments, ORDER_WRITE",
        "/api/inventory/parts/1001/adjustments, PRODUCTION_WRITE",
        "/api/inventory/parts/1001/adjustments, ACCOUNT_ADMIN",
        "/api/production/productions/101/work-order, ORDER_WRITE",
        "/api/production/productions/101/work-order, PURCHASE_WRITE",
        "/api/production/productions/101/work-order, ACCOUNT_ADMIN"
    })
    void otherPermissionCannotWrite(String path, String permission) {
        post(path, permission).expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "{1} 권한으로 {0} 을 고칠 수 있다")
    @CsvSource({
        "/api/inventory/parts/1001, PURCHASE_WRITE",
        "/api/purchases/suppliers/1, PURCHASE_WRITE",
        "/api/production/products/1, PRODUCTION_WRITE"
    })
    void allowedPermissionCanUpdateMasterData(String path, String permission) {
        request(HttpMethod.PUT, path, permission).expectStatus().isNotFound();
    }

    @ParameterizedTest(name = "{1} 권한으로 {0} 을 고칠 수 없다")
    @CsvSource({
        "/api/inventory/parts/1001, ORDER_WRITE",
        "/api/inventory/parts/1001, PRODUCTION_WRITE",
        "/api/inventory/parts/1001, ACCOUNT_ADMIN",
        "/api/purchases/suppliers/1, ORDER_WRITE",
        "/api/purchases/suppliers/1, PRODUCTION_WRITE",
        "/api/purchases/suppliers/1, ACCOUNT_ADMIN",
        "/api/production/products/1, ORDER_WRITE",
        "/api/production/products/1, PURCHASE_WRITE",
        "/api/production/products/1, ACCOUNT_ADMIN"
    })
    void otherPermissionCannotUpdateMasterData(String path, String permission) {
        request(HttpMethod.PUT, path, permission).expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "READ 만으로는 {0} 을 할 수 없다")
    @ValueSource(
            strings = {
                "/api/orders",
                "/api/orders/customers",
                "/api/mes/work-orders/1/steps/PRESS/start",
                "/api/purchases",
                "/api/inventory/finished-vehicles/" + VIN + "/ship",
                "/api/inventory/parts",
                "/api/production/products"
            })
    void readPermissionCannotWrite(String path) {
        post(path, "READ").expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "권한 표에 없는 {0} 역할은 아무것도 할 수 없다")
    @ValueSource(strings = {"SALES", "WORKER", "QUALITY"})
    void roleNotInTableHasNoPermission(String role) {
        // 표에 없는 역할 (WORKER 는 #118 에서 PRODUCTION 으로 합쳐 없어졌다)
        post("/api/orders", role).expectStatus().isForbidden();
        post("/api/mes/work-orders/1/steps/PRESS/start", role).expectStatus().isForbidden();
        post("/api/auth/admin/users", role).expectStatus().isForbidden();
        request(HttpMethod.GET, "/api/mes/work-orders", role).expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "{0} 역할도 내 정보를 볼 수 있다")
    @ValueSource(strings = {"READ", "ACCOUNT_ADMIN", "WORKER"})
    void anyLoggedInRoleCanReadMe(String role) {
        // 내 정보는 권한 없이 로그인한 직원 모두 (권한 없는 역할, 표에 없는 역할 포함)
        request(HttpMethod.GET, "/api/auth/me", role).expectStatus().isNotFound();
    }

    @Test
    void meNeedsLogin() {
        client.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
    }

    @ParameterizedTest(name = "표에 없는 요청은 {0} 권한으로도 할 수 없다")
    @ValueSource(
            strings = {
                "READ",
                "ORDER_WRITE",
                "PRODUCTION_WRITE",
                "PURCHASE_WRITE",
                "ACCOUNT_ADMIN",
                "READ+ORDER_WRITE+PRODUCTION_WRITE+PURCHASE_WRITE+ACCOUNT_ADMIN"
            })
    void requestNotInTheTableIsDeniedToEveryone(String permissions) {
        // 설비 등록은 만들지 않았다 (docs/specs/master-data.md)
        post("/api/mes/equipments", permissions).expectStatus().isForbidden();
        request(HttpMethod.PUT, "/api/orders/" + ORDER_NO, permissions)
                .expectStatus()
                .isForbidden();
        request(HttpMethod.DELETE, "/api/purchases/1", permissions).expectStatus().isForbidden();
    }

    @Test
    void accountAdminOnlyIssuesAccounts() {
        // ADMIN 의 권한(ACCOUNT_ADMIN)은 계정 발급만 연다. 업무 조회도 못 한다 (#118)
        post("/api/auth/admin/users", "ACCOUNT_ADMIN").expectStatus().isNotFound();
        request(HttpMethod.GET, "/api/auth/admin/roles", "ACCOUNT_ADMIN")
                .expectStatus()
                .isNotFound();
        for (String path : readPaths()) {
            request(HttpMethod.GET, path, "ACCOUNT_ADMIN").expectStatus().isForbidden();
        }
    }

    @Test
    void readPermissionCanRead() {
        for (String path : readPaths()) {
            request(HttpMethod.GET, path, "READ").expectStatus().isNotFound();
        }
    }

    @ParameterizedTest(name = "READ 없이 {0} 만으로는 조회할 수 없다")
    @ValueSource(strings = {"ORDER_WRITE", "PRODUCTION_WRITE", "PURCHASE_WRITE"})
    void writePermissionAloneCannotRead(String permission) {
        for (String path : readPaths()) {
            request(HttpMethod.GET, path, permission).expectStatus().isForbidden();
        }
    }

    private static String[] readPaths() {
        return new String[] {
            "/api/orders",
            "/api/orders/customers",
            "/api/production/productions/awaiting-release",
            "/api/purchases?status=ORDERED",
            "/api/mes/work-orders",
            "/api/inventory/parts",
            "/api/inventory/storage-slots"
        };
    }

    @Test
    void readingStillNeedsLogin() {
        client.get().uri("/api/inventory/parts").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/orders").exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec post(String path, String permissions) {
        return request(HttpMethod.POST, path, permissions);
    }

    private WebTestClient.ResponseSpec request(HttpMethod method, String path, String permissions) {
        return client.method(method)
                .uri(path)
                .header("Authorization", "Bearer " + permissions)
                .exchange();
    }
}

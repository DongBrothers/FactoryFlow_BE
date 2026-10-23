package com.factoryflow.gateway.config;

import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import io.lettuce.core.resource.ClientResources;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * gateway 가 역할 → 권한 표를 Redis 로 받아 판단한다 (docs/specs/role-permission-admin.md). 실제 Redis 에 auth 처럼 표를
 * 쓰고 발행한다. 통과하면 StubBackend 가 200, 막히면 403 이다.
 */
class RolePermissionSyncTest extends GatewayTestSupport {

    @Autowired ClientResources clientResources;

    @Test
    void tableFromChannelDecidesAccess() throws InterruptedException {
        publishTable(Map.of("READ", List.of("READ"), "SALES", List.of("READ")));
        awaitPermissions("SALES", List.of("READ"));

        get("/api/orders/1", "SALES").expectStatus().isOk();
        post("/api/orders", "SALES").expectStatus().isForbidden();

        // 관리자가 SALES 에 주문 쓰기를 더함
        publishTable(Map.of("READ", List.of("READ"), "SALES", List.of("ORDER_WRITE", "READ")));
        awaitPermissions("SALES", List.of("ORDER_WRITE", "READ"));

        post("/api/orders", "SALES").expectStatus().isOk();
    }

    @Test
    void reconnectToRedisReloadsTableFromKey() throws InterruptedException {
        // 끊긴 동안 온 신호를 놓친 상황: 키에는 새 표가 있지만 gateway 는 받지 못했다
        writeTableKeyOnly(Map.of("READ", List.of("READ"), "SALES", List.of("ORDER_WRITE")));
        post("/api/orders", "SALES").expectStatus().isForbidden();

        reconnect();
        awaitPermissions("SALES", List.of("ORDER_WRITE"));

        post("/api/orders", "SALES").expectStatus().isOk();
    }

    @Test
    void withoutTableEverythingIsDeniedUntilKeyAppears() throws InterruptedException {
        // 뜰 때 Redis 키가 없던 상황 (auth 가 아직 표를 쓰지 않음)
        redis.delete(TABLE_KEY);
        rolePermissionTable.replace(Map.of());
        reconnect();

        get("/api/orders/1", "READ").expectStatus().isForbidden();
        // 공개 경로는 표와 상관없다
        client.post().uri("/api/auth/login").exchange().expectStatus().isOk();

        // auth 가 키를 쓰면 gateway 가 다시 읽어 풀린다 (발행 없이 키만 있어도)
        writeTableKeyOnly(Map.of("READ", List.of("READ")));
        awaitPermissions("READ", List.of("READ"));

        get("/api/orders/1", "READ").expectStatus().isOk();
    }

    /** Lettuce 가 Redis 에 다시 연결된 것처럼 알린다. */
    private void reconnect() {
        clientResources
                .eventBus()
                .publish(
                        new ConnectionActivatedEvent(
                                new InetSocketAddress("localhost", 1),
                                new InetSocketAddress("localhost", 6379)));
    }

    private WebTestClient.ResponseSpec get(String path, String role) {
        return client.get().uri(path).header("Authorization", "Bearer " + role).exchange();
    }

    private WebTestClient.ResponseSpec post(String path, String role) {
        return client.post().uri(path).header("Authorization", "Bearer " + role).exchange();
    }
}

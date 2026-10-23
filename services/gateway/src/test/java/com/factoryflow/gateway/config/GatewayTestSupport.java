package com.factoryflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.test.container.TestContainersConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * gateway 통합 테스트 공통 설정. 실제 gateway 를 띄우고, dev 와 같은 규칙(/api/{서비스}/** 에 StripPrefix=1)의 라우트를
 * StubBackend 로 연결한다. 토큰 해석만 RoleTokenDecoder 로 바꾼다. 역할 → 권한 표는 실제처럼 Redis 로 받는다: 테스트마다 READ 역할
 * (READ 권한)만 있는 표를 Redis 에 보내고 gateway 가 받을 때까지 기다린다. 이 클래스를 상속한 테스트는 컨텍스트(와 컨테이너)를 함께 쓴다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.cors.allowed-origins=" + GatewayTestSupport.ALLOWED_ORIGIN,
            "app.role-permissions.retry-interval=200ms"
        })
@AutoConfigureWebTestClient
@Import({TestContainersConfig.class, RoleTokenDecoder.class})
abstract class GatewayTestSupport {

    static final String ALLOWED_ORIGIN = "https://factoryflow.dev";
    static final StubBackend BACKEND = StubBackend.start();

    static final String TABLE_KEY = "auth:role-permissions";
    static final String CHANNEL = "auth:role-permissions-changed";

    @Autowired WebTestClient client;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;
    @Autowired RolePermissionTable rolePermissionTable;

    /** 아무도 듣지 않는 포트. 이 포트로 가는 라우트(down)는 뒤 서비스 연결 실패를 만든다. */
    static final int CLOSED_PORT = closedPort();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        route(registry, 0, "orders", BACKEND.url());
        route(registry, 1, "auth", BACKEND.url());
        route(registry, 2, "down", "http://localhost:" + CLOSED_PORT);
    }

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @BeforeEach
    void seedRolePermissions() throws InterruptedException {
        publishTable(Map.of("READ", List.of("READ")));
        awaitPermissions("READ", List.of("READ"));
    }

    /** auth 처럼 표를 Redis 키에 쓰고 채널에 발행한다. */
    void publishTable(Map<String, List<String>> table) {
        String json = objectMapper.writeValueAsString(table);
        redis.opsForValue().set(TABLE_KEY, json);
        redis.convertAndSend(CHANNEL, json);
    }

    /** 키에만 쓴다 (발행하지 않음). gateway 가 키를 다시 읽어야만 반영된다. */
    void writeTableKeyOnly(Map<String, List<String>> table) {
        redis.opsForValue().set(TABLE_KEY, objectMapper.writeValueAsString(table));
    }

    /** gateway 가 들고 있는 표에서 역할의 권한이 기대와 같아질 때까지 (최대 5초) 기다린다. */
    void awaitPermissions(String role, List<String> permissions) throws InterruptedException {
        for (int i = 0; i < 50 && !permissionsOf(role).equals(Set.copyOf(permissions)); i++) {
            Thread.sleep(100);
        }
        assertThat(permissionsOf(role)).isEqualTo(Set.copyOf(permissions));
    }

    private Set<String> permissionsOf(String role) {
        return rolePermissionTable.authorities(role).stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private static void route(
            DynamicPropertyRegistry registry, int index, String service, String uri) {
        String prefix = "spring.cloud.gateway.server.webflux.routes[" + index + "].";
        registry.add(prefix + "id", () -> service);
        registry.add(prefix + "uri", () -> uri);
        registry.add(prefix + "predicates[0]", () -> "Path=/api/" + service + "/**");
        registry.add(prefix + "filters[0]", () -> "StripPrefix=1");
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

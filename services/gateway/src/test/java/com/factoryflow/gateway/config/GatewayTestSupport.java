package com.factoryflow.gateway.config;

import com.factoryflow.test.container.TestContainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * gateway 통합 테스트 공통 설정. 실제 gateway 를 띄우고, dev 와 같은 규칙(/api/{서비스}/** 에 StripPrefix=1)의 라우트를
 * StubBackend 로 연결한다. 토큰 해석만 RoleTokenDecoder 로 바꾼다. 이 클래스를 상속한 테스트는 컨텍스트(와 컨테이너)를 함께 쓴다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cors.allowed-origins=" + GatewayTestSupport.ALLOWED_ORIGIN)
@AutoConfigureWebTestClient
@Import({TestContainersConfig.class, RoleTokenDecoder.class})
abstract class GatewayTestSupport {

    static final String ALLOWED_ORIGIN = "https://factoryflow.dev";
    static final StubBackend BACKEND = StubBackend.start();

    @Autowired WebTestClient client;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        route(registry, 0, "orders");
        route(registry, 1, "auth");
    }

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static void route(DynamicPropertyRegistry registry, int index, String service) {
        String prefix = "spring.cloud.gateway.server.webflux.routes[" + index + "].";
        registry.add(prefix + "id", () -> service);
        registry.add(prefix + "uri", BACKEND::url);
        registry.add(prefix + "predicates[0]", () -> "Path=/api/" + service + "/**");
        registry.add(prefix + "filters[0]", () -> "StripPrefix=1");
    }
}

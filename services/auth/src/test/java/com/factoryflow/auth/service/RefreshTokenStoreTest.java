package com.factoryflow.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.auth.config.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RefreshTokenStoreTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    RefreshTokenStore store;

    @BeforeEach
    void setUp() {
        var connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        var redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        store =
                new RefreshTokenStore(
                        redisTemplate,
                        new AuthProperties(
                                "gmail.com",
                                Duration.ofHours(24),
                                Duration.ofMinutes(15),
                                Duration.ofDays(14),
                                5,
                                Duration.ofMinutes(15)));
    }

    @Test
    void consumeReturnsPublicIdForCurrentToken() {
        String token = store.issue("user-7");

        assertThat(store.consume(token)).isEqualTo("user-7");
    }

    @Test
    void reissueInvalidatesPreviousToken() {
        // 로그인/재발급 때 새로 발급하면 사용자당 1개만 유효 → 이전 토큰은 무효
        String old = store.issue("user-7");
        String fresh = store.issue("user-7");

        assertThat(store.consume(old)).isNull();
        assertThat(store.consume(fresh)).isEqualTo("user-7");
    }

    @Test
    void revokeInvalidatesToken() {
        String token = store.issue("user-7");

        store.revoke(token);

        assertThat(store.consume(token)).isNull();
    }

    @Test
    void rejectsMalformedOrNullToken() {
        assertThat(store.consume("no-user-prefix")).isNull();
        assertThat(store.consume(null)).isNull();
    }
}

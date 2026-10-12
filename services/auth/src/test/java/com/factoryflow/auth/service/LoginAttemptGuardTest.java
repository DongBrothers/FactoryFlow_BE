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
class LoginAttemptGuardTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    LoginAttemptGuard guard;

    @BeforeEach
    void setUp() {
        var connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        var redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        // 임계치 3회, 잠금 창 1분
        guard =
                new LoginAttemptGuard(
                        redisTemplate,
                        new AuthProperties(
                                "gmail.com",
                                Duration.ofHours(24),
                                Duration.ofMinutes(15),
                                Duration.ofDays(14),
                                3,
                                Duration.ofMinutes(1)));
    }

    @Test
    void notLockedBelowThreshold() {
        guard.recordFailure("emp-below");
        guard.recordFailure("emp-below");

        assertThat(guard.isLocked("emp-below")).isFalse();
    }

    @Test
    void lockedAtThreshold() {
        guard.recordFailure("emp-at");
        guard.recordFailure("emp-at");
        guard.recordFailure("emp-at");

        assertThat(guard.isLocked("emp-at")).isTrue();
    }

    @Test
    void resetUnlocks() {
        guard.recordFailure("emp-reset");
        guard.recordFailure("emp-reset");
        guard.recordFailure("emp-reset");
        guard.reset("emp-reset");

        assertThat(guard.isLocked("emp-reset")).isFalse();
    }
}

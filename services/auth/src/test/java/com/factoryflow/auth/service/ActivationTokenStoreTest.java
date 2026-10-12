package com.factoryflow.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.PendingAccount;
import com.factoryflow.auth.domain.Role;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ActivationTokenStoreTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    static final PendingAccount KIM = new PendingAccount("kim@gmail.com", "김철수", Role.SALES);

    StringRedisTemplate redisTemplate;
    ActivationTokenStore store;

    @BeforeEach
    void setUp() {
        var connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        var keys = redisTemplate.keys("auth:*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        store = new ActivationTokenStore(redisTemplate, properties(Duration.ofMinutes(5)));
    }

    private AuthProperties properties(Duration activationTokenTtl) {
        return new AuthProperties(
                "gmail.com",
                activationTokenTtl,
                Duration.ofMinutes(15),
                Duration.ofDays(14),
                5,
                Duration.ofMinutes(15));
    }

    @Test
    void issueStoresTokenKeyAndEmailKey() {
        String token = store.issue(KIM);

        assertThat(redisTemplate.hasKey("auth:activation:" + token)).isTrue();
        assertThat(redisTemplate.opsForValue().get("auth:pending:kim@gmail.com")).isEqualTo(token);
    }

    @Test
    void consumeReturnsPendingOnceAndRemovesBothKeys() {
        String token = store.issue(KIM);

        assertThat(store.consume(token)).isEqualTo(KIM);
        // 1회용: 같은 토큰 재사용은 거절된다
        assertThat(store.consume(token)).isNull();
        assertThat(redisTemplate.keys("auth:*")).isEmpty();
    }

    @Test
    void reissueForSameEmailInvalidatesOldToken() {
        String oldToken = store.issue(KIM);
        String newToken = store.issue(KIM);

        assertThat(store.consume(oldToken)).isNull();
        assertThat(store.consume(newToken)).isEqualTo(KIM);
    }

    @Test
    void consumeReturnsNullForUnknownToken() {
        assertThat(store.consume("does-not-exist")).isNull();
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        // TTL 로 만료된 토큰은 거절된다 (짧은 TTL 로 실증)
        var shortLived =
                new ActivationTokenStore(redisTemplate, properties(Duration.ofMillis(300)));
        String token = shortLived.issue(KIM);

        Thread.sleep(500);

        assertThat(shortLived.consume(token)).isNull();
        assertThat(redisTemplate.hasKey("auth:pending:kim@gmail.com")).isFalse();
    }

    @Test
    void concurrentIssueForSameEmailLeavesOnlyOneValidToken() throws Exception {
        // 같은 이메일로 동시에 발급해도 "덮어쓰기" 가 지켜진다: 유효한 토큰은 auth:pending 이 가리키는 하나뿐
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls =
                    IntStream.range(0, 8)
                            .mapToObj(i -> (Callable<String>) () -> store.issue(KIM))
                            .toList();
            pool.invokeAll(calls);
        } finally {
            pool.shutdown();
        }

        String current = redisTemplate.opsForValue().get("auth:pending:kim@gmail.com");
        assertThat(redisTemplate.keys("auth:activation:*"))
                .containsExactly("auth:activation:" + current);
        assertThat(redisTemplate.getExpire("auth:activation:" + current)).isPositive();
    }
}

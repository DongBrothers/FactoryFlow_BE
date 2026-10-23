package com.factoryflow.auth.service.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.PendingAccount;
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

    static final PendingAccount KIM = new PendingAccount("kim@gmail.com", "김철수", "SALES");

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

    @Test
    void restorePutsConsumedTokenBack() {
        // 활성화 저장이 실패하면 꺼낸 토큰을 되돌려 같은 링크로 다시 시도할 수 있다
        String token = store.issue(KIM);
        store.consume(token);

        store.restore(token, KIM);

        assertThat(redisTemplate.opsForValue().get("auth:pending:kim@gmail.com")).isEqualTo(token);
        assertThat(redisTemplate.getExpire("auth:activation:" + token)).isPositive();
        assertThat(store.consume(token)).isEqualTo(KIM);
    }

    @Test
    void restoreDoesNotOverrideNewerIssue() {
        // 되돌리기 전에 관리자가 다시 발급했다면 새 링크만 유효하다 (덮어쓰기 규칙)
        String oldToken = store.issue(KIM);
        store.consume(oldToken);
        String newToken = store.issue(KIM);

        store.restore(oldToken, KIM);

        assertThat(store.consume(oldToken)).isNull();
        assertThat(store.consume(newToken)).isEqualTo(KIM);
    }

    @Test
    void cancelRemovesBothKeysOfThatIssue() {
        // 활성화 메일을 보내지 못하면 이번 발급의 대기 정보를 지운다
        String token = store.issue(KIM);

        store.cancel(token, KIM.email());

        assertThat(redisTemplate.keys("auth:*")).isEmpty();
    }

    @Test
    void cancelKeepsNewerIssueForSameEmail() {
        // 지우기 전에 같은 이메일로 다시 발급됐다면, 새 발급은 남긴다
        String oldToken = store.issue(KIM);
        String newToken = store.issue(KIM);

        store.cancel(oldToken, KIM.email());

        // 이메일 키도 새 발급을 가리켜야 다음 재발급 때 새 링크를 무효로 만들 수 있다
        assertThat(redisTemplate.opsForValue().get("auth:pending:kim@gmail.com"))
                .isEqualTo(newToken);
        assertThat(store.consume(newToken)).isEqualTo(KIM);
    }
}

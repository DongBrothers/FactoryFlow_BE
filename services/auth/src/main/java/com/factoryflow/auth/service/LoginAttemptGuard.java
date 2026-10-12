package com.factoryflow.auth.service;

import com.factoryflow.auth.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 로그인 실패 횟수를 Redis 에 세어, 임계치를 넘으면 일시 잠근다. 무차별 대입 방지. */
@Component
@RequiredArgsConstructor
public class LoginAttemptGuard {

    private static final String PREFIX = "auth:login-fail:";

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    public boolean isLocked(String email) {
        String count = redis.opsForValue().get(PREFIX + email);
        return count != null && Integer.parseInt(count) >= properties.maxLoginAttempts();
    }

    public void recordFailure(String email) {
        String key = PREFIX + email;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, properties.loginLockWindow());
        }
    }

    public void reset(String email) {
        redis.delete(PREFIX + email);
    }
}

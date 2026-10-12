package com.factoryflow.auth.service.redis;

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

    /** 실패마다 만료를 다시 건다. 만료가 빠진 카운터가 남아 영구 잠금이 되는 일을 막는다(잠금은 마지막 실패부터 잠금 창 동안). */
    public void recordFailure(String email) {
        String key = PREFIX + email;
        redis.opsForValue().increment(key);
        redis.expire(key, properties.loginLockWindow());
    }

    public void reset(String email) {
        redis.delete(PREFIX + email);
    }
}

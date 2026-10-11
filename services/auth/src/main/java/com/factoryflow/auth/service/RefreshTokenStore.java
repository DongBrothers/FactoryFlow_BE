package com.factoryflow.auth.service;

import com.factoryflow.auth.config.AuthProperties;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Refresh 토큰을 Redis 에 보관한다. TTL 로 만료되고, consume 시 삭제되어 재발급 때 회전(rotation)된다. */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String PREFIX = "auth:refresh:";

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    /** 새 Refresh 토큰을 발급해 userId 와 함께 TTL 로 저장하고, 토큰을 반환한다. */
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue()
                .set(PREFIX + token, String.valueOf(userId), properties.refreshTokenTtl());
        return token;
    }

    /** 토큰을 소비한다(조회 후 즉시 삭제 = 회전). 없거나 만료면 null. */
    public Long consume(String token) {
        String userId = redis.opsForValue().getAndDelete(PREFIX + token);
        return userId == null ? null : Long.valueOf(userId);
    }

    /** 로그아웃: 토큰을 폐기한다. */
    public void revoke(String token) {
        redis.delete(PREFIX + token);
    }
}

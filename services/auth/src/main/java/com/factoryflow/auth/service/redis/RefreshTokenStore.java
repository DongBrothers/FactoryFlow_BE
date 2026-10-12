package com.factoryflow.auth.service.redis;

import com.factoryflow.auth.config.AuthProperties;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Refresh 토큰을 Redis 에 사용자당 하나씩 보관한다(키 = publicId). 로그인/재발급 때 새로 발급하면 이전 토큰이 덮어써져 무효가 되고(단일 세션),
 * 로그아웃 때 폐기한다. 토큰은 "publicId.랜덤" 형식이라 토큰만으로 소유자(publicId)를 찾을 수 있다. (docs/specs/auth-account.md)
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String PREFIX = "auth:refresh:"; // + publicId

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    /** 새 Refresh 토큰 발급. 같은 사용자의 이전 토큰은 덮어써져 무효가 된다(재발급). */
    public String issue(String publicId) {
        String token = publicId + "." + UUID.randomUUID();
        redis.opsForValue().set(PREFIX + publicId, token, properties.refreshTokenTtl());
        return token;
    }

    /** 소비(회전): 저장된 현재 토큰과 일치하면 지우고 publicId, 이전·위조·만료·이미 쓴 토큰이면 null. */
    public String consume(String token) {
        String publicId = parseOwner(token);
        if (publicId == null || !deleteIfCurrent(publicId, token)) {
            return null;
        }
        return publicId;
    }

    /** 로그아웃: 제시한 토큰이 현재 토큰이면 사용자의 Refresh 를 폐기한다. */
    public void revoke(String token) {
        String publicId = parseOwner(token);
        if (publicId != null) {
            deleteIfCurrent(publicId, token);
        }
    }

    private boolean deleteIfCurrent(String publicId, String token) {
        Long deleted =
                redis.execute(RedisScripts.DELETE_IF_EQUALS, List.of(PREFIX + publicId), token);
        return deleted != null && deleted == 1L;
    }

    private String parseOwner(String token) {
        if (token == null) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        return token.substring(0, dot);
    }
}

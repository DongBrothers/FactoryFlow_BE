package com.factoryflow.auth.service;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.PendingAccount;
import com.factoryflow.auth.domain.Role;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 발급된 대기 계정을 Redis 에 두 키로 보관한다. (docs/specs/auth-account.md "Redis 키")
 *
 * <ul>
 *   <li>auth:activation:{token} → 대기 정보(hash). 링크의 토큰으로 찾는다
 *   <li>auth:pending:{email} → token. 같은 이메일 재발급 때 옛 토큰을 지운다
 * </ul>
 *
 * 둘 다 TTL 로 만료되고, consume 시 삭제되어 1회만 쓸 수 있다.
 */
@Component
@RequiredArgsConstructor
public class ActivationTokenStore {

    private static final String TOKEN_PREFIX = "auth:activation:";
    private static final String EMAIL_PREFIX = "auth:pending:";

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    /** 대기 계정을 저장하고 새 토큰을 반환한다. 같은 이메일의 옛 토큰은 무효가 된다(덮어쓰기). */
    public String issue(PendingAccount pending) {
        String emailKey = EMAIL_PREFIX + pending.email();
        String oldToken = redis.opsForValue().get(emailKey);
        if (oldToken != null) {
            redis.delete(TOKEN_PREFIX + oldToken);
        }

        String token = UUID.randomUUID().toString();
        String tokenKey = TOKEN_PREFIX + token;
        redis.opsForHash()
                .putAll(
                        tokenKey,
                        Map.of(
                                "email", pending.email(),
                                "name", pending.name(),
                                "role", pending.role().name()));
        redis.expire(tokenKey, properties.activationTokenTtl());
        redis.opsForValue().set(emailKey, token, properties.activationTokenTtl());
        return token;
    }

    /** 토큰을 소비한다(조회 후 두 키 삭제). 없거나 만료·이미 쓴 토큰이면 null. */
    public PendingAccount consume(String token) {
        String tokenKey = TOKEN_PREFIX + token;
        Map<Object, Object> fields = redis.opsForHash().entries(tokenKey);
        // 동시에 같은 토큰을 쓰면 delete 에 성공한 한 요청만 통과한다
        if (fields.isEmpty() || !Boolean.TRUE.equals(redis.delete(tokenKey))) {
            return null;
        }
        var pending =
                new PendingAccount(
                        (String) fields.get("email"),
                        (String) fields.get("name"),
                        Role.valueOf((String) fields.get("role")));
        String emailKey = EMAIL_PREFIX + pending.email();
        if (token.equals(redis.opsForValue().get(emailKey))) {
            redis.delete(emailKey);
        }
        return pending;
    }
}

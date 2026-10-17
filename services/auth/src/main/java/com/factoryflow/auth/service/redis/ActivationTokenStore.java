package com.factoryflow.auth.service.redis;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.PendingAccount;
import com.factoryflow.auth.domain.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 발급된 대기 계정을 Redis 에 두 키로 보관한다 (docs/specs/auth-account.md "Redis 키"). auth:activation:{token} 에는
 * 대기 정보(hash)를 두어 링크의 토큰으로 찾고, auth:pending:{email} 에는 token 을 두어 같은 이메일 재발급 때 옛 토큰을 지운다. 둘 다 TTL 로
 * 만료되고, consume 시 삭제되어 1회만 쓸 수 있다.
 */
@Component
@RequiredArgsConstructor
public class ActivationTokenStore {

    private static final String TOKEN_PREFIX = "auth:activation:";
    private static final String EMAIL_PREFIX = "auth:pending:";

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    /** 대기 계정을 저장하고 새 토큰을 반환한다. 같은 이메일의 옛 토큰은 무효가 된다(덮어쓰기). 한 번에 처리해 동시 발급에도 토큰은 하나만 남는다. */
    public String issue(PendingAccount pending) {
        String token = UUID.randomUUID().toString();
        save(RedisScripts.ISSUE_ACTIVATION, token, pending);
        return token;
    }

    /**
     * 소비한 토큰을 되돌린다(활성화 저장이 실패했을 때). 그 사이 같은 이메일로 다시 발급됐으면 되돌리지 않는다.
     *
     * <p>ponytail: TTL 을 남은 시간이 아니라 처음부터 다시 건다(최대 24시간 연장). 드문 장애 복구라 단순하게 둔다.
     */
    public void restore(String token, PendingAccount pending) {
        save(RedisScripts.RESTORE_ACTIVATION, token, pending);
    }

    /** 발급을 취소한다(활성화 메일을 보내지 못했을 때). 그 사이 같은 이메일로 다시 발급됐으면 새 발급은 남긴다. */
    public void cancel(String token, String email) {
        redis.execute(
                RedisScripts.CANCEL_ACTIVATION,
                List.of(EMAIL_PREFIX + email, TOKEN_PREFIX + token),
                token);
    }

    private void save(RedisScript<Long> script, String token, PendingAccount pending) {
        redis.execute(
                script,
                List.of(EMAIL_PREFIX + pending.email(), TOKEN_PREFIX + token),
                TOKEN_PREFIX,
                pending.email(),
                pending.name(),
                pending.role().name(),
                String.valueOf(properties.activationTokenTtl().toMillis()),
                token);
    }

    /** 토큰을 소비한다(조회 후 두 키 삭제). 없거나 만료·이미 쓴 토큰이면 null. */
    public PendingAccount consume(String token) {
        String tokenKey = TOKEN_PREFIX + token;
        Map<Object, Object> fields = redis.opsForHash().entries(tokenKey);
        // 동시에 같은 토큰을 쓰면 delete 에 성공한 한 요청만 통과한다
        if (fields.isEmpty() || !Boolean.TRUE.equals(redis.delete(tokenKey))) {
            return null;
        }
        PendingAccount pending =
                new PendingAccount(
                        (String) fields.get("email"),
                        (String) fields.get("name"),
                        Role.fromStored((String) fields.get("role")));
        redis.execute(
                RedisScripts.DELETE_IF_EQUALS, List.of(EMAIL_PREFIX + pending.email()), token);
        return pending;
    }
}

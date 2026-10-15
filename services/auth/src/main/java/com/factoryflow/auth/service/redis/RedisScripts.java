package com.factoryflow.auth.service.redis;

import org.springframework.data.redis.core.script.RedisScript;

/** 조회와 변경을 한 번에(원자적으로) 해야 하는 Redis 작업. 동시 요청이 사이에 끼어들지 못한다. */
final class RedisScripts {

    /** KEYS[1] 의 값이 ARGV[1] 과 같을 때만 지운다. 지웠으면 1, 아니면 0. */
    static final RedisScript<Long> DELETE_IF_EQUALS =
            RedisScript.of(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1])"
                            + " else return 0 end",
                    Long.class);

    /**
     * 활성화 대기 저장. KEYS[1] = auth:pending:{email}, KEYS[2] = auth:activation:{새 token}. ARGV = 토큰 키
     * prefix, email, name, role, TTL(ms), 새 token. 같은 이메일의 옛 토큰 키를 지우고 두 키를 같은 TTL 로 저장한다.
     */
    static final RedisScript<Long> ISSUE_ACTIVATION =
            RedisScript.of(
                    """
                    local old = redis.call('GET', KEYS[1])
                    if old then redis.call('DEL', ARGV[1] .. old) end
                    redis.call('HSET', KEYS[2], 'email', ARGV[2], 'name', ARGV[3], 'role', ARGV[4])
                    redis.call('PEXPIRE', KEYS[2], ARGV[5])
                    redis.call('SET', KEYS[1], ARGV[6], 'PX', ARGV[5])
                    return 1
                    """,
                    Long.class);

    /**
     * 활성화 대기 되돌리기. KEYS 와 ARGV 는 ISSUE_ACTIVATION 과 같다. 그 사이 같은 이메일로 다시 발급됐으면(auth:pending 이 있으면) 새
     * 링크가 유효하므로 아무것도 하지 않는다. 되돌리면 1, 아니면 0.
     */
    static final RedisScript<Long> RESTORE_ACTIVATION =
            RedisScript.of(
                    """
                    if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
                    redis.call('HSET', KEYS[2], 'email', ARGV[2], 'name', ARGV[3], 'role', ARGV[4])
                    redis.call('PEXPIRE', KEYS[2], ARGV[5])
                    redis.call('SET', KEYS[1], ARGV[6], 'PX', ARGV[5])
                    return 1
                    """,
                    Long.class);

    /**
     * 활성화 대기 취소(메일을 보내지 못했을 때). KEYS[1] = auth:pending:{email}, KEYS[2] = auth:activation:{token},
     * ARGV[1] = token. 토큰 키를 지우고, 이메일 키는 아직 이 토큰을 가리킬 때만 지운다 (그 사이 다시 발급됐으면 새 발급은 남긴다).
     */
    static final RedisScript<Long> CANCEL_ACTIVATION =
            RedisScript.of(
                    """
                    redis.call('DEL', KEYS[2])
                    if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) end
                    return 1
                    """,
                    Long.class);

    /** KEYS[1] 을 1 늘리고 만료(ARGV[1], ms)를 다시 건다. 둘을 한 번에 해 만료 없는 카운터가 남지 않는다. 늘린 값을 돌려준다. */
    static final RedisScript<Long> INCREMENT_WITH_EXPIRE =
            RedisScript.of(
                    "local count = redis.call('INCR', KEYS[1]) redis.call('PEXPIRE', KEYS[1], ARGV[1])"
                            + " return count",
                    Long.class);

    private RedisScripts() {}
}

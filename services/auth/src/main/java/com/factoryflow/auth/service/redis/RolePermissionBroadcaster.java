package com.factoryflow.auth.service.redis;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 역할 → 권한 표 전체를 Redis 키에 쓰고 채널에 발행한다. gateway 는 뜰 때 키를 읽고, 채널로 새 표를 받는다
 * (docs/specs/role-permission-admin.md). 원본은 DB 이고 키는 복사본이라 TTL 을 두지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RolePermissionBroadcaster {

    private static final String TABLE_KEY = "auth:role-permissions";
    private static final String CHANNEL = "auth:role-permissions-changed";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 표: 역할 코드 → 권한 코드 목록. 실패하면 Redis 예외를 그대로 던진다. */
    public void broadcast(Map<String, List<String>> table) {
        String json = objectMapper.writeValueAsString(table);
        redis.opsForValue().set(TABLE_KEY, json);
        redis.convertAndSend(CHANNEL, json);
    }
}

package com.factoryflow.gateway.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * 역할 → 권한 표의 복사본 (원본은 auth DB). auth 가 Redis 로 보낸 표를 RolePermissionSubscriber 가 넣는다. 요청마다 토큰의 role
 * 로 권한을 찾는다 (docs/specs/role-permission-admin.md). 표를 받기 전에는 비어 있어 모든 역할이 권한이 없다.
 */
@Component
public class RolePermissionTable {

    /** 표는 통째로 바꿔 끼운다. 요청 처리 중 반쯤 바뀐 표를 보지 않는다. */
    private final AtomicReference<Map<String, List<GrantedAuthority>>> table =
            new AtomicReference<>(Map.of());

    /** 표: 역할 코드 → 권한 코드 목록. */
    public void replace(Map<String, List<String>> permissionsByRole) {
        table.set(
                permissionsByRole.entrySet().stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey,
                                        entry ->
                                                entry.getValue().stream()
                                                        .<GrantedAuthority>map(
                                                                SimpleGrantedAuthority::new)
                                                        .toList())));
    }

    /** 역할의 권한. 표에 없는 역할(또는 role 이 없는 토큰)은 권한이 없다. */
    public List<GrantedAuthority> authorities(String role) {
        return role == null ? List.of() : table.get().getOrDefault(role, List.of());
    }
}

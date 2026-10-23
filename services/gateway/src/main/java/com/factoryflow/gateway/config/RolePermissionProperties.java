package com.factoryflow.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 역할 → 권한 표 받기 설정. retryInterval 은 Redis 키가 없거나 읽지 못할 때 다시 읽는 간격이다. 표를 읽으면 멈춘다 (평소에는 주기적으로 읽지 않는다,
 * docs/specs/role-permission-admin.md).
 */
@ConfigurationProperties(prefix = "app.role-permissions")
public record RolePermissionProperties(Duration retryInterval) {}

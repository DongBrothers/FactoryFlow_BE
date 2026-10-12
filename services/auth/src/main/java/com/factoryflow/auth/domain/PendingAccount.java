package com.factoryflow.auth.domain;

/**
 * 발급됐지만 아직 활성화하지 않은 계정. DB 가 아니라 Redis 에 24시간 대기하고, 활성화 때 UserAccount 로 저장된다.
 * (docs/specs/auth-account.md)
 */
public record PendingAccount(String email, String name, Role role) {}

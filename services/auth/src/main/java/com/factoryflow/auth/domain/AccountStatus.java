package com.factoryflow.auth.domain;

/** 계정 상태. INVITED(발급됨) → ACTIVE(활성). DISABLED(비활성). (docs/specs/auth-account.md) */
public enum AccountStatus {
    INVITED,
    ACTIVE,
    DISABLED
}

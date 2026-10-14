package com.factoryflow.auth.domain;

/** 사원 역할. 관리자가 발급 시 배정한다. 1인 1역할. (docs/specs/auth-account.md) */
public enum Role {
    SALES,
    PURCHASE,
    PRODUCTION,
    WORKER,
    ADMIN
}

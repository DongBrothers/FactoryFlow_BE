package com.factoryflow.auth.domain;

/** 계정 상태. 활성화가 끝난 계정만 DB 에 저장되므로 ACTIVE 로 시작한다. DISABLED 는 로그인 불가. (docs/specs/auth-account.md) */
public enum AccountStatus {
    ACTIVE,
    DISABLED
}

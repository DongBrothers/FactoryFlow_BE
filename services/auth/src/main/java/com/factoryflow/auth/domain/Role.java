package com.factoryflow.auth.domain;

/**
 * 사원 역할. 관리자가 발급 시 배정한다. 1인 1역할. (docs/specs/auth-account.md, docs/specs/role-permissions.md). 라인
 * 작업자 WORKER 는 생산 PRODUCTION 으로 합쳤다 (#118).
 */
public enum Role {
    SALES,
    PURCHASE,
    PRODUCTION,
    ADMIN;

    /** 합치기 전에 저장된 역할 값. 발급 대기(Redis)에 남아 있을 수 있다. */
    private static final String MERGED_WORKER = "WORKER";

    /** 저장된 역할 값을 읽는다. 합치기 전의 WORKER 는 PRODUCTION 으로 읽는다. */
    public static Role fromStored(String value) {
        return MERGED_WORKER.equals(value) ? PRODUCTION : valueOf(value);
    }
}

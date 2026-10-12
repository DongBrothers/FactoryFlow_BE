package com.factoryflow.auth.dto.response;

import com.factoryflow.auth.domain.PendingAccount;
import com.factoryflow.auth.domain.Role;

/** 발급(대기)된 계정 정보. 아직 DB 에 없으므로 id 가 없고, 활성화 토큰은 포함하지 않는다(메일로만 전달). */
public record IssueAccountResponse(String email, String name, Role role) {

    public static IssueAccountResponse from(PendingAccount pending) {
        return new IssueAccountResponse(pending.email(), pending.name(), pending.role());
    }
}

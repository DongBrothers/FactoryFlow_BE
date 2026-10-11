package com.factoryflow.auth.dto.response;

import com.factoryflow.auth.domain.AccountStatus;
import com.factoryflow.auth.domain.Role;
import com.factoryflow.auth.domain.UserAccount;

/** 발급된 계정 정보. 활성화 토큰은 포함하지 않는다(메일로만 전달). */
public record IssuedAccountResponse(
        Long id, String employeeNo, String email, String name, Role role, AccountStatus status) {

    public static IssuedAccountResponse from(UserAccount account) {
        return new IssuedAccountResponse(
                account.getId(),
                account.getEmployeeNo(),
                account.getEmail(),
                account.getName(),
                account.getRole(),
                account.getStatus());
    }
}

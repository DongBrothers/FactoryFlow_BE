package com.factoryflow.auth.dto.response;

import com.factoryflow.auth.domain.PendingAccount;
import com.factoryflow.auth.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/** 발급(대기)된 계정 정보. 아직 DB 에 없으므로 id 가 없고, 활성화 토큰은 포함하지 않는다(메일로만 전달). */
@Schema(description = "직원 계정 발급 응답")
public record IssueAccountResponse(
        @Schema(description = "직원 이메일 (소문자로 정규화됨)", example = "kim@gmail.com") String email,
        @Schema(description = "직원 이름", example = "김철수") String name,
        @Schema(description = "역할", example = "SALES") Role role) {

    public static IssueAccountResponse from(PendingAccount pending) {
        return new IssueAccountResponse(pending.email(), pending.name(), pending.role());
    }
}

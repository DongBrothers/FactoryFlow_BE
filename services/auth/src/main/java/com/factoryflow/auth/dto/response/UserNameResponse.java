package com.factoryflow.auth.dto.response;

import com.factoryflow.auth.domain.UserAccount;
import io.swagger.v3.oas.annotations.media.Schema;

/** 직원 이름. 다른 서비스가 public_id 로만 가진 직원(실적 작업자, 발주자)을 화면에 이름으로 보인다. 이메일·역할은 넣지 않는다. */
@Schema(description = "직원 이름")
public record UserNameResponse(
        @Schema(description = "직원 식별자 (JWT sub)", example = "6c1e2f3a-8b4d-4f7e-9a2c-1d3e5f7a9b0c")
                String publicId,
        @Schema(description = "직원 이름", example = "김철수") String name) {

    public static UserNameResponse from(UserAccount account) {
        return new UserNameResponse(account.getPublicId(), account.getName());
    }
}

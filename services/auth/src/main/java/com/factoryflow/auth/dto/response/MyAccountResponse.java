package com.factoryflow.auth.dto.response;

import com.factoryflow.auth.domain.UserAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 내 정보. 프론트가 화면 상단 이름과 메뉴(권한)를 고른다. 이메일은 넣지 않는다 (docs/specs/role-permission-admin.md). */
@Schema(description = "내 정보")
public record MyAccountResponse(
        @Schema(description = "직원 식별자 (JWT sub)", example = "6c1e2f3a-8b4d-4f7e-9a2c-1d3e5f7a9b0c")
                String publicId,
        @Schema(description = "직원 이름", example = "김철수") String name,
        @Schema(description = "역할 코드", example = "SALES") String role,
        @Schema(description = "역할이 가진 권한 코드 (코드 순)", example = "[\"ORDER_WRITE\", \"READ\"]")
                List<String> permissions) {

    public static MyAccountResponse of(UserAccount account, String role, List<String> permissions) {
        return new MyAccountResponse(account.getPublicId(), account.getName(), role, permissions);
    }
}

package com.factoryflow.auth.dto.response;

import com.factoryflow.auth.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "역할")
public record RoleResponse(
        @Schema(description = "역할 코드. 계정 발급의 role 로 보낸다", example = "SALES") String code,
        @Schema(description = "화면에 보이는 이름", example = "영업") String name,
        @Schema(description = "역할이 가진 권한 코드 (코드 순)", example = "[\"ORDER_WRITE\", \"READ\"]")
                List<String> permissions) {

    public static RoleResponse of(Role role, List<String> permissions) {
        return new RoleResponse(role.getCode(), role.getName(), permissions);
    }
}

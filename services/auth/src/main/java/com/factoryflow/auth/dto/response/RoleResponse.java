package com.factoryflow.auth.dto.response;

import com.factoryflow.auth.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "역할")
public record RoleResponse(
        @Schema(description = "역할 코드. 계정 발급의 role 로 보낸다", example = "SALES") String code,
        @Schema(description = "화면에 보이는 이름", example = "영업") String name) {

    public static RoleResponse from(Role role) {
        return new RoleResponse(role.getCode(), role.getName());
    }
}

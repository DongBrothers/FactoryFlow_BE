package com.factoryflow.auth.dto.response;

import com.factoryflow.auth.domain.Permission;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "권한")
public record PermissionResponse(
        @Schema(description = "권한 코드. 역할 권한 바꾸기의 permissions 로 보낸다", example = "ORDER_WRITE")
                String code,
        @Schema(description = "화면에 보이는 이름", example = "주문 쓰기") String name) {

    public static PermissionResponse from(Permission permission) {
        return new PermissionResponse(permission.getCode(), permission.getName());
    }
}

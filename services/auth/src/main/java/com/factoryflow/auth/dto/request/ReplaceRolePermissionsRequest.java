package com.factoryflow.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 역할의 권한을 통째로 바꾸는 요청 (docs/specs/role-permission-admin.md). */
@Schema(description = "역할 권한 바꾸기 요청")
public record ReplaceRolePermissionsRequest(
        @Schema(
                        description = "역할이 가질 권한 코드 전부 (권한 목록의 code). 빈 목록이면 권한 없는 역할",
                        example = "[\"READ\", \"ORDER_WRITE\"]")
                @NotNull
                List<@NotBlank @Size(max = 30) String> permissions) {}

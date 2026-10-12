package com.factoryflow.auth.dto.request;

import com.factoryflow.auth.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 관리자 계정 발급 요청. */
@Schema(description = "직원 계정 발급 요청")
public record IssueAccountRequest(
        @Schema(description = "직원 이메일 (로그인 ID, 허용 도메인만)", example = "kim@gmail.com")
                @NotBlank
                @Email
                @Size(max = 100)
                String email,
        @Schema(description = "직원 이름", example = "김철수") @NotBlank @Size(max = 50) String name,
        @Schema(description = "역할 (1인 1역할)", example = "SALES") @NotNull Role role) {}

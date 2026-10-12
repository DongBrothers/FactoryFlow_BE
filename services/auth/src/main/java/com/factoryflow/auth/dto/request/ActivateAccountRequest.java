package com.factoryflow.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 직원 활성화 요청. token 은 메일로 받은 일회용 활성화 토큰. */
@Schema(description = "계정 활성화 요청")
public record ActivateAccountRequest(
        @Schema(
                        description = "메일 링크의 활성화 토큰 (1회용, 24시간)",
                        example = "3f2c9a1e-7b4d-4c2a-9e8f-1a2b3c4d5e6f")
                @NotBlank
                String token,
        @Schema(description = "새 비밀번호", example = "pw-1234!") @NotBlank String password) {}

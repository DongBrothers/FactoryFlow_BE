package com.factoryflow.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 로그인 요청. 이메일 + 비밀번호. */
@Schema(description = "로그인 요청")
public record LoginRequest(
        @Schema(description = "이메일 (대소문자 무시)", example = "kim@gmail.com") @NotBlank String email,
        @Schema(description = "비밀번호", example = "pw-1234!") @NotBlank String password) {}

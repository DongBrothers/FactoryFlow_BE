package com.factoryflow.auth.dto.request;

import com.factoryflow.auth.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 관리자 계정 발급 요청. */
public record IssueAccountRequest(
        @NotBlank @Email String email, @NotBlank String name, @NotNull Role role) {}

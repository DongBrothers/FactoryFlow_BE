package com.factoryflow.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 직원 활성화 요청. token 은 메일로 받은 일회용 활성화 토큰. */
public record ActivateAccountRequest(@NotBlank String token, @NotBlank String password) {}

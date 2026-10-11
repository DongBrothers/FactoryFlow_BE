package com.factoryflow.auth.controller;

import com.factoryflow.auth.dto.request.ActivationRequest;
import com.factoryflow.auth.dto.request.IssueAccountRequest;
import com.factoryflow.auth.dto.request.LoginRequest;
import com.factoryflow.auth.dto.request.LogoutRequest;
import com.factoryflow.auth.dto.request.RefreshRequest;
import com.factoryflow.auth.dto.response.IssuedAccountResponse;
import com.factoryflow.auth.dto.response.TokenResponse;
import com.factoryflow.auth.service.AuthService;
import com.factoryflow.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 관리자 계정 발급. (역할=ADMIN 접근 제어는 Phase 4) */
    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssuedAccountResponse> issue(
            @Valid @RequestBody IssueAccountRequest request) {
        return ApiResponse.created(authService.issue(request));
    }

    /** 사원 활성화(메일의 토큰 + 비밀번호). */
    @PostMapping("/activation")
    public ApiResponse<Void> activate(@Valid @RequestBody ActivationRequest request) {
        authService.activate(request);
        return ApiResponse.noContent();
    }

    /** 로그인(사번 + 비밀번호) → Access/Refresh 발급. */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /** Access 토큰 재발급(Refresh 회전). */
    @PostMapping("/token/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    /** 로그아웃(Refresh 폐기). */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ApiResponse.noContent();
    }
}

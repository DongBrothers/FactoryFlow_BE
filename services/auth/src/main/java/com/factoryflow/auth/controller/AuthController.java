package com.factoryflow.auth.controller;

import com.factoryflow.auth.config.RefreshCookieFactory;
import com.factoryflow.auth.dto.request.ActivateAccountRequest;
import com.factoryflow.auth.dto.request.IssueAccountRequest;
import com.factoryflow.auth.dto.request.LoginRequest;
import com.factoryflow.auth.dto.response.AccessTokenResponse;
import com.factoryflow.auth.dto.response.IssueAccountResponse;
import com.factoryflow.auth.service.AuthService;
import com.factoryflow.auth.service.IssuedTokens;
import com.factoryflow.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
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
    private final RefreshCookieFactory refreshCookieFactory;

    /** 관리자 계정 발급. DB 에 저장하지 않고 Redis 에 대기시킨다. ADMIN 확인은 AdminRoleInterceptor. */
    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssueAccountResponse> issue(
            @Valid @RequestBody IssueAccountRequest request) {
        IssueAccountResponse response = authService.issue(request);
        return ApiResponse.created(response);
    }

    /** 직원 활성화(메일의 토큰 + 비밀번호). 이때 계정이 DB 에 처음 저장된다. */
    @PostMapping("/activation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@Valid @RequestBody ActivateAccountRequest request) {
        authService.activate(request);
    }

    /** 로그인(이메일 + 비밀번호) → Access 는 바디, Refresh 는 httpOnly 쿠키. */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        IssuedTokens tokens = authService.login(request);
        return withRefreshCookie(tokens);
    }

    /** Access 토큰 재발급(쿠키의 Refresh 를 회전). */
    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> refresh(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false)
                    String refreshToken) {
        IssuedTokens tokens = authService.refresh(refreshToken);
        return withRefreshCookie(tokens);
    }

    /** 로그아웃(쿠키의 Refresh 폐기 + 쿠키 만료). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false)
                    String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.expired().toString())
                .build();
    }

    private ResponseEntity<ApiResponse<AccessTokenResponse>> withRefreshCookie(
            IssuedTokens tokens) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.create(tokens.refreshToken()).toString())
                .body(ApiResponse.ok(AccessTokenResponse.from(tokens)));
    }
}

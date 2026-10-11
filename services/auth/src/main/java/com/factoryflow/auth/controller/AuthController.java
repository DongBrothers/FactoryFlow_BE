package com.factoryflow.auth.controller;

import com.factoryflow.auth.dto.request.ActivationRequest;
import com.factoryflow.auth.dto.request.IssueAccountRequest;
import com.factoryflow.auth.dto.response.IssuedAccountResponse;
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
}

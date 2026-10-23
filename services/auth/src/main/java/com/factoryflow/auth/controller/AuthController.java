package com.factoryflow.auth.controller;

import com.factoryflow.auth.config.RefreshCookieFactory;
import com.factoryflow.auth.dto.request.ActivateAccountRequest;
import com.factoryflow.auth.dto.request.IssueAccountRequest;
import com.factoryflow.auth.dto.request.LoginRequest;
import com.factoryflow.auth.dto.request.ReplaceRolePermissionsRequest;
import com.factoryflow.auth.dto.response.AccessTokenResponse;
import com.factoryflow.auth.dto.response.IssueAccountResponse;
import com.factoryflow.auth.dto.response.MyAccountResponse;
import com.factoryflow.auth.dto.response.PermissionResponse;
import com.factoryflow.auth.dto.response.RoleResponse;
import com.factoryflow.auth.service.AuthService;
import com.factoryflow.auth.service.IssuedTokens;
import com.factoryflow.auth.service.RoleService;
import com.factoryflow.common.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "직원 계정 발급·활성화·로그인·토큰")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RoleService roleService;
    private final RefreshCookieFactory refreshCookieFactory;

    @Operation(
            summary = "직원 계정 발급 (관리자)",
            description =
                    "이메일·이름·역할로 계정을 발급한다. DB 에 저장하지 않고 Redis 에 24시간 대기시킨 뒤 활성화 링크를 보낸다. 같은 이메일의"
                            + " 대기 건이 있으면 덮어써 옛 링크는 무효가 된다. ADMIN 확인은 gateway 가 JWT 의 role 로 한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "발급됨 (대기). 아직 DB 에 없으므로 id 는 없다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류, 허용되지 않은 이메일 도메인 (AUTH-003), 없는 역할 (AUTH-013)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "이미 활성화된 이메일 (AUTH-002)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "활성화 메일을 보내지 못함 (AUTH-012). 대기 정보는 남지 않으므로 다시 발급하면 된다")
    })
    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssueAccountResponse> issue(
            @Valid @RequestBody IssueAccountRequest request) {
        IssueAccountResponse response = authService.issue(request);
        return ApiResponse.created(response);
    }

    @Operation(
            summary = "역할 목록 (관리자)",
            description =
                    "계정 발급 화면의 역할 선택지와 권한 편집 화면. role 테이블의 역할을 표시 순서대로 한 번에 주고(기준 정보라 페이지 없음),"
                            + " 역할마다 가진 권한 코드를 준다. ACCOUNT_ADMIN 확인은 gateway 가 한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공")
    })
    @GetMapping("/admin/roles")
    public ApiResponse<List<RoleResponse>> findRoles() {
        List<RoleResponse> roles = roleService.findRoles();
        return ApiResponse.ok(roles);
    }

    @Operation(
            summary = "권한 목록 (관리자)",
            description = "권한 편집 화면의 선택지. 권한 코드 순으로 한 번에 준다. 권한 종류는 마이그레이션으로만 늘어난다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공")
    })
    @GetMapping("/admin/permissions")
    public ApiResponse<List<PermissionResponse>> findPermissions() {
        List<PermissionResponse> permissions = roleService.findPermissions();
        return ApiResponse.ok(permissions);
    }

    @Operation(
            summary = "역할 권한 바꾸기 (관리자)",
            description =
                    "역할의 권한을 요청한 목록으로 통째로 바꾼다. 같은 코드가 여러 번 오면 한 번으로 본다. 저장 뒤 표를 Redis 로 보내 gateway 에"
                            + " 1초 안팎에 반영된다. 같은 내용으로 다시 보내도 결과가 같다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "바뀐 역할"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류, 없는 권한 코드 (AUTH-015)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "없는 역할 (AUTH-014)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "바꾸면 ACCOUNT_ADMIN 을 가진 역할이 하나도 없음 (AUTH-016). 바뀌지 않는다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description =
                        "저장했지만 Redis 에 쓰지 못해 gateway 에 반영되지 않음 (AUTH-017). 같은 내용으로 다시 저장하면 된다")
    })
    @PutMapping("/admin/roles/{code}/permissions")
    public ApiResponse<RoleResponse> replacePermissions(
            @Parameter(description = "역할 코드", example = "SALES")
                    @PathVariable("code")
                    @NotBlank
                    @Size(max = 20)
                    String code,
            @Valid @RequestBody ReplaceRolePermissionsRequest request) {
        RoleResponse role = roleService.replacePermissions(code, request);
        return ApiResponse.ok(role);
    }

    @Operation(
            summary = "내 정보",
            description =
                    "로그인한 직원의 이름, 역할, 권한. 프론트가 화면 상단 이름과 메뉴를 고른다. 이메일은 주지 않는다. 로그인한 직원 모두(ADMIN"
                            + " 포함) 부를 수 있다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "X-User-Id 없음 (gateway 를 거치지 않은 요청)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "없는 계정 (AUTH-018)")
    })
    @GetMapping("/me")
    public ApiResponse<MyAccountResponse> findMe(
            @Parameter(description = "로그인한 직원 (gateway 가 넣는 JWT sub)", example = "6c1e2f3a-…")
                    @RequestHeader("X-User-Id")
                    @NotBlank
                    @Size(max = 36)
                    String publicId) {
        MyAccountResponse me = authService.findMe(publicId);
        return ApiResponse.ok(me);
    }

    @Operation(
            summary = "계정 활성화",
            description = "메일 링크의 토큰과 새 비밀번호로 계정을 활성화한다. 이때 계정이 DB 에 ACTIVE 로 처음 저장된다. 토큰은 1회용이다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "활성화됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류, 없거나 만료·이미 쓴 토큰 (AUTH-004)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "같은 이메일 계정이 이미 있음 (AUTH-002). 재시도가 아니라 로그인하면 된다")
    })
    @PostMapping("/activation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@Valid @RequestBody ActivateAccountRequest request) {
        authService.activate(request);
    }

    @Operation(
            summary = "로그인",
            description =
                    "이메일(대소문자 무시) + 비밀번호로 로그인한다. Access 토큰은 본문, Refresh 토큰은 httpOnly 쿠키(refreshToken)로"
                            + " 내린다. 연속 실패가 많으면 잠시 잠긴다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "로그인됨. Set-Cookie 로 Refresh 토큰"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "요청 형식 오류"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "없는 이메일 또는 틀린 비밀번호 (AUTH-006)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "비활성화된 계정 (AUTH-007)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "실패가 많아 일시 잠김 (AUTH-008)")
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        IssuedTokens tokens = authService.login(request);
        return withRefreshCookie(tokens);
    }

    @Operation(
            summary = "Access 토큰 재발급",
            description =
                    "쿠키(refreshToken)의 Refresh 토큰으로 새 Access·Refresh 를 발급한다. 옛 Refresh 는 무효가 된다(회전).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "재발급됨. Set-Cookie 로 새 Refresh 토큰"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "쿠키 없음, 옛·위조·만료 Refresh 토큰 (AUTH-009)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "비활성화된 계정 (AUTH-007)")
    })
    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> refresh(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false)
                    String refreshToken) {
        IssuedTokens tokens = authService.refresh(refreshToken);
        return withRefreshCookie(tokens);
    }

    @Operation(
            summary = "로그아웃",
            description = "쿠키(refreshToken)의 Refresh 토큰을 폐기하고 쿠키를 만료시킨다. 쿠키가 없거나 옛 토큰이어도 성공한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "로그아웃됨. Set-Cookie 로 만료된 쿠키")
    })
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

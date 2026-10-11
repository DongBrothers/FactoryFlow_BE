package com.factoryflow.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.AccountStatus;
import com.factoryflow.auth.domain.Role;
import com.factoryflow.auth.domain.UserAccount;
import com.factoryflow.auth.dto.request.ActivationRequest;
import com.factoryflow.auth.dto.request.IssueAccountRequest;
import com.factoryflow.auth.dto.request.LoginRequest;
import com.factoryflow.auth.dto.request.LogoutRequest;
import com.factoryflow.auth.dto.request.RefreshRequest;
import com.factoryflow.auth.dto.response.TokenResponse;
import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.auth.repository.UserAccountRepository;
import com.factoryflow.common.web.exception.CustomException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserAccountRepository repository;
    @Mock ActivationTokenStore activationTokenStore;
    @Mock ActivationNotifier notifier;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtProvider jwtProvider;
    @Mock RefreshTokenStore refreshTokenStore;
    @Mock LoginAttemptGuard loginAttemptGuard;

    AuthService authService;

    @BeforeEach
    void setUp() {
        var properties =
                new AuthProperties(
                        "gmail.com",
                        Duration.ofHours(24),
                        Duration.ofMinutes(15),
                        Duration.ofDays(14),
                        5,
                        Duration.ofMinutes(15));
        authService =
                new AuthService(
                        repository,
                        activationTokenStore,
                        notifier,
                        passwordEncoder,
                        properties,
                        jwtProvider,
                        refreshTokenStore,
                        loginAttemptGuard);
    }

    private UserAccount activeAccount(long id) {
        var account = UserAccount.invite("E1001", "a@gmail.com", "홍길동", Role.WORKER);
        account.activate("hash");
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    // --- 발급 ---

    @Test
    void issue_createsInvitedAccountAndSendsActivationToken() {
        var saved = UserAccount.invite("E1001", "a@gmail.com", "홍길동", Role.WORKER);
        ReflectionTestUtils.setField(saved, "id", 1L);
        when(repository.existsByEmployeeNo("E1001")).thenReturn(false);
        when(repository.existsByEmail("a@gmail.com")).thenReturn(false);
        when(repository.save(any(UserAccount.class))).thenReturn(saved);
        when(activationTokenStore.issue(1L)).thenReturn("tok-123");

        authService.issue(new IssueAccountRequest("E1001", "a@gmail.com", "홍길동", Role.WORKER));

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(repository).save(captor.capture());
        UserAccount created = captor.getValue();
        assertThat(created.getStatus()).isEqualTo(AccountStatus.INVITED);
        assertThat(created.getRole()).isEqualTo(Role.WORKER);
        assertThat(created.getPassword()).isNull();
        verify(notifier).sendActivationLink("a@gmail.com", "tok-123");
    }

    @Test
    void issue_rejectsDisallowedEmailDomain() {
        assertThatThrownBy(
                        () ->
                                authService.issue(
                                        new IssueAccountRequest(
                                                "E1001", "a@naver.com", "홍길동", Role.WORKER)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.INVALID_EMAIL_DOMAIN);
        verify(repository, never()).save(any());
    }

    @Test
    void issue_rejectsDuplicateEmployeeNo() {
        when(repository.existsByEmployeeNo("E1001")).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                authService.issue(
                                        new IssueAccountRequest(
                                                "E1001", "a@gmail.com", "홍길동", Role.WORKER)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.DUPLICATE_EMPLOYEE_NO);
        verify(repository, never()).save(any());
    }

    @Test
    void issue_rejectsDuplicateEmail() {
        when(repository.existsByEmployeeNo("E1001")).thenReturn(false);
        when(repository.existsByEmail("a@gmail.com")).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                authService.issue(
                                        new IssueAccountRequest(
                                                "E1001", "a@gmail.com", "홍길동", Role.WORKER)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.DUPLICATE_EMAIL);
        verify(repository, never()).save(any());
    }

    // --- 활성화 ---

    @Test
    void activate_setsPasswordAndActivatesAccount() {
        var account = UserAccount.invite("E1001", "a@gmail.com", "홍길동", Role.WORKER);
        when(activationTokenStore.consume("tok-123")).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(account));
        when(passwordEncoder.encode("pw1234")).thenReturn("hashed");

        authService.activate(new ActivationRequest("tok-123", "pw1234"));

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getPassword()).isEqualTo("hashed");
    }

    @Test
    void activate_rejectsInvalidOrExpiredToken() {
        when(activationTokenStore.consume("bad")).thenReturn(null);

        assertThatThrownBy(() -> authService.activate(new ActivationRequest("bad", "pw1234")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.INVALID_ACTIVATION_TOKEN);
    }

    @Test
    void activate_rejectsAlreadyActivatedAccount() {
        var account = UserAccount.invite("E1001", "a@gmail.com", "홍길동", Role.WORKER);
        account.activate("already");
        when(activationTokenStore.consume("tok-123")).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(account));
        when(passwordEncoder.encode("pw1234")).thenReturn("hashed");

        assertThatThrownBy(() -> authService.activate(new ActivationRequest("tok-123", "pw1234")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.ACCOUNT_NOT_INVITED);
    }

    // --- 로그인 ---

    @Test
    void login_issuesTokensAndResetsFailureCount() {
        var account = activeAccount(7L);
        when(loginAttemptGuard.isLocked("E1001")).thenReturn(false);
        when(repository.findByEmployeeNo("E1001")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtProvider.createAccessToken(account)).thenReturn("access-jwt");
        when(refreshTokenStore.issue(7L)).thenReturn("refresh-tok");

        TokenResponse response = authService.login(new LoginRequest("E1001", "pw"));

        assertThat(response).isEqualTo(new TokenResponse("access-jwt", "refresh-tok"));
        verify(loginAttemptGuard).reset("E1001");
    }

    @Test
    void login_recordsFailureOnWrongPassword() {
        var account = activeAccount(7L);
        when(loginAttemptGuard.isLocked("E1001")).thenReturn(false);
        when(repository.findByEmployeeNo("E1001")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("E1001", "wrong")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.LOGIN_FAILED);
        verify(loginAttemptGuard).recordFailure("E1001");
    }

    @Test
    void login_recordsFailureOnUnknownEmployee() {
        when(loginAttemptGuard.isLocked("E1001")).thenReturn(false);
        when(repository.findByEmployeeNo("E1001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("E1001", "pw")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.LOGIN_FAILED);
        verify(loginAttemptGuard).recordFailure("E1001");
    }

    @Test
    void login_rejectsWhenLocked() {
        when(loginAttemptGuard.isLocked("E1001")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("E1001", "pw")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.ACCOUNT_LOCKED);
        verify(repository, never()).findByEmployeeNo(any());
    }

    @Test
    void login_rejectsInactiveAccount() {
        var invited = UserAccount.invite("E1001", "a@gmail.com", "홍길동", Role.WORKER);
        when(loginAttemptGuard.isLocked("E1001")).thenReturn(false);
        when(repository.findByEmployeeNo("E1001")).thenReturn(Optional.of(invited));

        assertThatThrownBy(() -> authService.login(new LoginRequest("E1001", "pw")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.ACCOUNT_NOT_ACTIVE);
    }

    // --- 재발급 / 로그아웃 ---

    @Test
    void refresh_rotatesTokens() {
        var account = activeAccount(7L);
        when(refreshTokenStore.consume("old-ref")).thenReturn(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(account));
        when(jwtProvider.createAccessToken(account)).thenReturn("access2");
        when(refreshTokenStore.issue(7L)).thenReturn("new-ref");

        TokenResponse response = authService.refresh(new RefreshRequest("old-ref"));

        assertThat(response).isEqualTo(new TokenResponse("access2", "new-ref"));
    }

    @Test
    void refresh_rejectsInvalidToken() {
        when(refreshTokenStore.consume("bad")).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("bad")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void logout_revokesRefreshToken() {
        authService.logout(new LogoutRequest("ref-tok"));

        verify(refreshTokenStore).revoke("ref-tok");
    }
}

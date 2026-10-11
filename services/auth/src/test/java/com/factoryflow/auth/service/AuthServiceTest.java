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
    @Mock ActivationTokenStore tokenStore;
    @Mock ActivationNotifier notifier;
    @Mock PasswordEncoder passwordEncoder;

    AuthService authService;

    @BeforeEach
    void setUp() {
        var properties = new AuthProperties("gmail.com", Duration.ofHours(24));
        authService =
                new AuthService(repository, tokenStore, notifier, passwordEncoder, properties);
    }

    @Test
    void issue_createsInvitedAccountAndSendsActivationToken() {
        var saved = UserAccount.invite("E1001", "a@gmail.com", "홍길동", Role.WORKER);
        ReflectionTestUtils.setField(saved, "id", 1L);
        when(repository.existsByEmployeeNo("E1001")).thenReturn(false);
        when(repository.existsByEmail("a@gmail.com")).thenReturn(false);
        when(repository.save(any(UserAccount.class))).thenReturn(saved);
        when(tokenStore.issue(1L)).thenReturn("tok-123");

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

    @Test
    void activate_setsPasswordAndActivatesAccount() {
        var account = UserAccount.invite("E1001", "a@gmail.com", "홍길동", Role.WORKER);
        when(tokenStore.consume("tok-123")).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(account));
        when(passwordEncoder.encode("pw1234")).thenReturn("hashed");

        authService.activate(new ActivationRequest("tok-123", "pw1234"));

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getPassword()).isEqualTo("hashed");
    }

    @Test
    void activate_rejectsInvalidOrExpiredToken() {
        when(tokenStore.consume("bad")).thenReturn(null);

        assertThatThrownBy(() -> authService.activate(new ActivationRequest("bad", "pw1234")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.INVALID_ACTIVATION_TOKEN);
    }

    @Test
    void activate_rejectsAlreadyActivatedAccount() {
        var account = UserAccount.invite("E1001", "a@gmail.com", "홍길동", Role.WORKER);
        account.activate("already");
        when(tokenStore.consume("tok-123")).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(account));
        when(passwordEncoder.encode("pw1234")).thenReturn("hashed");

        assertThatThrownBy(() -> authService.activate(new ActivationRequest("tok-123", "pw1234")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.ACCOUNT_NOT_INVITED);
    }
}

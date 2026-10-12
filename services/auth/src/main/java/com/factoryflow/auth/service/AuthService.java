package com.factoryflow.auth.service;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.AccountStatus;
import com.factoryflow.auth.domain.UserAccount;
import com.factoryflow.auth.dto.request.ActivationRequest;
import com.factoryflow.auth.dto.request.IssueAccountRequest;
import com.factoryflow.auth.dto.request.LoginRequest;
import com.factoryflow.auth.dto.response.IssuedAccountResponse;
import com.factoryflow.auth.dto.response.TokenResponse;
import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.auth.repository.UserAccountRepository;
import com.factoryflow.common.web.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountRepository repository;
    private final ActivationTokenStore activationTokenStore;
    private final ActivationNotifier notifier;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginAttemptGuard loginAttemptGuard;

    /** 관리자 발급: INVITED 계정 생성 후 활성화 토큰을 발행·발송한다. */
    @Transactional
    public IssuedAccountResponse issue(IssueAccountRequest request) {
        validateEmailDomain(request.email());
        if (repository.existsByEmployeeNo(request.employeeNo())) {
            throw new CustomException(AuthErrorCode.DUPLICATE_EMPLOYEE_NO);
        }
        if (repository.existsByEmail(request.email())) {
            throw new CustomException(AuthErrorCode.DUPLICATE_EMAIL);
        }

        UserAccount account =
                repository.save(
                        UserAccount.invite(
                                request.employeeNo(),
                                request.email(),
                                request.name(),
                                request.role()));
        String token = activationTokenStore.issue(account.getId());
        notifier.sendActivationLink(account.getEmail(), token);
        return IssuedAccountResponse.from(account);
    }

    /** 사원 활성화: 토큰을 소비해 계정을 찾고 비번 설정 후 ACTIVE 로 전환한다. */
    @Transactional
    public void activate(ActivationRequest request) {
        Long userId = activationTokenStore.consume(request.token());
        if (userId == null) {
            throw new CustomException(AuthErrorCode.INVALID_ACTIVATION_TOKEN);
        }
        UserAccount account =
                repository
                        .findById(userId)
                        .orElseThrow(
                                () -> new CustomException(AuthErrorCode.INVALID_ACTIVATION_TOKEN));
        account.activate(passwordEncoder.encode(request.password()));
    }

    /** 로그인: 사번+비번 검증 후 Access/Refresh 발급. 실패는 세어 임계치 초과 시 잠근다. */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        String employeeNo = request.employeeNo();
        if (loginAttemptGuard.isLocked(employeeNo)) {
            throw new CustomException(AuthErrorCode.ACCOUNT_LOCKED);
        }

        UserAccount account = repository.findByEmployeeNo(employeeNo).orElse(null);
        if (account == null) {
            loginAttemptGuard.recordFailure(employeeNo);
            throw new CustomException(AuthErrorCode.LOGIN_FAILED);
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new CustomException(AuthErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        if (!passwordEncoder.matches(request.password(), account.getPassword())) {
            loginAttemptGuard.recordFailure(employeeNo);
            throw new CustomException(AuthErrorCode.LOGIN_FAILED);
        }

        loginAttemptGuard.reset(employeeNo);
        return issueTokens(account);
    }

    /** 재발급: Refresh(쿠키에서 읽음)를 소비(회전)하고 새 Access/Refresh 를 발급한다. */
    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        if (refreshToken == null) {
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        String publicId = refreshTokenStore.consume(refreshToken);
        if (publicId == null) {
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        UserAccount account =
                repository
                        .findByPublicId(publicId)
                        .orElseThrow(
                                () -> new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new CustomException(AuthErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        return issueTokens(account);
    }

    /** 로그아웃: Refresh 토큰(쿠키에서 읽음)을 폐기한다. */
    public void logout(String refreshToken) {
        if (refreshToken != null) {
            refreshTokenStore.revoke(refreshToken);
        }
    }

    private TokenResponse issueTokens(UserAccount account) {
        String accessToken = jwtProvider.createAccessToken(account);
        String refreshToken = refreshTokenStore.issue(account.getPublicId());
        return new TokenResponse(accessToken, refreshToken);
    }

    private void validateEmailDomain(String email) {
        String domain = properties.allowedEmailDomain();
        if (domain != null && !email.endsWith("@" + domain)) {
            throw new CustomException(AuthErrorCode.INVALID_EMAIL_DOMAIN);
        }
    }
}
